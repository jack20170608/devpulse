# 关系型存储设计 / Relational storage design

**状态 / Status**：提案，未实现 / Proposal, not implemented

[项目首页](../readme.MD)

## 中文

### 0. 实现记录（与提案的差异）

本节记录落地后的实际情况，正文保留原始设计以便对照。

| 项 | 提案 | 实现 |
|---|---|---|
| 访问层 | JDBC 直写 | **JDBI**：通用 `BaseDao<T, K>` + 领域 `RunDao` / `TaskDao`；子表与 upsert 仍为显式 SQL |
| 执行状态 | 保留 `state.conf` | **`state.conf` 已删除**，运行与任务状态进库 |
| 清单 | `plan.conf` | 保持 `plan.conf`（不可变输入，不是状态） |
| 库位置 | 未指定 | `.devpulse/state.mv.db` |
| schema | 4 张领域表 | 另加 `task_id`（日志/daemon 目录定位）、`executor_started_at`（中断恢复判定）；补三处外键 |
| `daemon_instance` | 在范围内 | **未建**，`DaemonManager` 仍写文件（下一步） |
| 指纹写入 | — | 新增 `updateTaskState()`：只写执行状态，避免不知道指纹的调用方把它抹掉 |
| 体积 | 预估 +2.5 MB | 实测 pulse jar **3.3 MB → 8.1 MB**（含 JDBI、commons-lang3、slf4j） |

### 1. 为什么现在做

这是一次**平台决策**，不是 retry 的补丁。目标是把运行的持久化从"每次重写一个配置文件"升级为一套数据模型，让 CLI 和未来的 web 端共用同一份事实。

现状的问题：

| 现状 | 问题 |
|------|------|
| `state.conf` 每次任务状态变化都整体重写 | 写放大，中断风险靠原子写兜底 |
| 每个 daemon 实例一个 `.conf` 文件 | 查询要遍历目录 |
| `history.list()` 遍历运行目录 | 运行多了会线性变慢 |
| 跨运行复用只能沿 `parentRunId` 走 | 无法回答"这个任务定义以前成功过吗" |

### 2. 范围与边界

**在范围内**：运行、任务、依赖边、daemon 实例、跨运行复用索引、schema 版本与迁移。

**不在范围内**：日志正文（继续用文件）、产物缓存内容、多机分布式、鉴权与用户体系。

### 3. 模块与依赖

新增模块 **`devpulse-storage`**：

- 对外只有 `RunStore` 接口与数据记录，不含业务逻辑
- 内含 JDBC 实现与 schema 迁移
- `devpulse-core` 依赖它；`RunHistory` 退化为薄封装（对外签名尽量不变）
- 未来 web 端可直接依赖它

理由：`core` 保持"引擎"职责，存储被隔离成可复用、可独立测试的边界。这会让核心模块从 3 个变成 4 个（core、storage、pulse、dist），是为平台化付出的结构成本。

### 4. 存储引擎

| | **H2（推荐）** | SQLite |
|---|---|---|
| 体积 | 约 2.5 MB，纯 Java | 约 12 MB，含各平台原生库 |
| 运行时 | 无原生依赖 | 需释放原生库到临时目录，受限环境下可能失败 |
| 人工检视 | 需经 JDBC | `sqlite3` 命令通用 |
| 未来服务端 | 可切 server 模式 | 单写者模型 |

**推荐 H2**：纯 Java 与"单 jar、零运行时"的定位一致，Windows 上无原生库释放风险。代价是失去 `sqlite3` 那种随手检视能力——由 `status` 的 JSON 视图补足，不为此新增命令。

**约束**：引擎选择必须可替换。`RunStore` 是不可变接口，SQL 只出现在实现类与迁移脚本中。

### 5. 数据模型

```text
run(id PK, project, target, parallelism, state, executor_pid,
    started_at, finished_at, bash, tool_version, parent_run_id)

task(run_id FK, name, fingerprint, state, exit_code, error,
     started_at, finished_at, log_path, reused_from,
     daemon_health, daemon_error, daemon_checked_at,
     PRIMARY KEY (run_id, name))

task_need(run_id, task_name, need_name,
          PRIMARY KEY (run_id, task_name, need_name))

daemon_instance(run_id, task_name, instance_id, attempt, pid, pid_started_at,
                started_at, ready_at, stopped_at, stop_command,
                PRIMARY KEY (run_id, task_name, instance_id))

task_result(name, fingerprint, run_id, finished_at,
            PRIMARY KEY (name, fingerprint))

schema_version(version, applied_at)
```

设计要点：

- **`task_need` 独立成表**，而不是把依赖序列化进 `task.needs`：web 端要按依赖关系查询与渲染，序列化字段查不了。
- **`task_result` 是跨运行复用索引**，也是 retry 的替代品：键存在且成功即可跳过。它按 `(name, fingerprint)` 唯一，不绑定父运行。
- **日志正文不入库**，`log_path` 只存相对路径。日志是流式、可能很大、且需要 `tail` 语义，放文件更合适。
- **`daemon_instance` 保留 `pid` + `pid_started_at`**：进程身份校验靠这两个字段，和现有 `Owner` 逻辑一致。

索引：`run(project, started_at DESC)`、`task(run_id)`、`task_result(name)`。

### 6. 并发与一致性

- 一次状态更新 = 一条 `UPDATE`，不再重写整份记录。
- 执行者互斥用 `run.executor_pid` + 存活校验，替代 `run.lock` 文件；仍需一把数据库级的写锁避免两个进程同时开始同一运行。
- 写入幂等：用 `MERGE`（H2）而不是 `INSERT`，重放不会产生重复行。
- 事务边界：`run` 行创建与 `task` 行初始化在同一个事务内，避免"有运行没有任务"的中间态。

### 7. 旧数据

**不做迁移。** 现有 `.devpulse/runs/` 目录保留但不再被读取；文档说明可安全删除。理由是当前只有一个使用者，而迁移代码本身会成为需要长期维护的兼容负担。

### 8. 对 retry 的影响

`RetryPlan` 的集合计算被一次查询替代：

```sql
SELECT run_id, finished_at FROM task_result
WHERE name = ? AND fingerprint = ? AND finished_at IS NOT NULL
```

由此的结论：

- `retry` 命令与 `RetryPlan` **删除**
- "跳过已完成节点"成为 `pulse` 的一个开关（建议 `--cached` / `--no-cached`，默认关闭以避免语义突变）
- `retry --dry-run` 能提供的"会重跑哪些"，由同一份查询在 `pulse --dry-run --format json` 中输出

### 9. 对未来 web 端的支撑

这份模型能直接回答：

- 某项目最近 N 次运行及其状态
- 某任务的历史耗时分布与失败率
- daemon 从启动到就绪的耗时分布
- 跨运行复用命中率

这些都不需要新增表；若将来需要实时推送，再单独设计事件流，不预埋在 schema 里。

### 10. 风险

| 风险 | 应对 |
|---|---|
| jar 增大约 2.5 MB | 可接受；`pulse` 当前 3.3 MB |
| 失去 `cat state.conf` 的直接检视 | `status --format json` 已覆盖绝大多数需要 |
| schema 演进 | 第一版就带 `schema_version` 与顺序迁移脚本，不允许"改表即改代码" |
| 单文件数据库的并发写 | 明确单写者约束；未来 web 多写者再评估 server 模式 |
| core 引入 JDBC | 通过 `devpulse-storage` 隔离，core 只依赖接口 |

### 11. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| 1 | `devpulse-storage` 模块、schema、迁移骨架、`RunStore` 接口与 H2 实现 | 接口测试通过，不接入生产路径 |
| 2 | `RunHistory` 改为基于 store，**CLI 行为零变化** | 现有全部测试通过，无新增行为 |
| 3 | `task_result` 接入，`pulse` 支持跳过已完成；删除 `retry`/`RetryPlan` | 跨运行复用生效，`retry` 相关测试移除 |
| 4 | web 端复用同一存储 | 后续 |

阶段 1、2 不改变任何用户可见行为，是纯内部替换，风险最低。

### 12. 需要确认

1. 引擎：H2（推荐）还是 SQLite
2. 日志：继续用文件（推荐）还是入库
3. 现在就加 `owner` / `tenant` 列吗？多人或多机使用才需要，建议暂不加
4. 旧运行数据：不迁移、不再读取（推荐）

## English

### 0. Implementation record (differences from the proposal)

This section records what was actually built; the body keeps the original design for comparison.

| Item | Proposal | Implementation |
|---|---|---|
| Access layer | Direct JDBC | **JDBI**: generic `BaseDao<T, K>` plus `RunDao` / `TaskDao`; child tables and upserts stay explicit SQL |
| Execution state | Keep `state.conf` | **`state.conf` deleted**; run and task state live in the database |
| Manifest | `plan.conf` | Kept as `plan.conf` (immutable input, not state) |
| Database location | Unspecified | `.devpulse/state.mv.db` |
| Schema | Four domain tables | Adds `task_id` (locating logs and daemon directories) and `executor_started_at` (interruption recovery); three foreign keys added |
| `daemon_instance` | In scope | **Not created**; `DaemonManager` still writes files (next step) |
| Fingerprint writes | — | New `updateTaskState()` writes only execution state, so a caller that does not know the fingerprint cannot erase it |
| Size | Estimated +2.5 MB | Measured pulse jar **3.3 MB → 8.1 MB** (JDBI, commons-lang3, slf4j) |

### 1. Why now

This is a **platform decision**, not a retry patch. The goal is to move run persistence from "rewrite a configuration file on every change" to one data model shared by the CLI and a future web client.

Current problems:

| Today | Problem |
|-------|---------|
| `state.conf` is rewritten whole on every task state change | Write amplification; interruption safety relies on atomic writes |
| One `.conf` file per daemon instance | Queries require directory walks |
| `history.list()` walks the run directories | Linear slowdown as history grows |
| Cross-run reuse can only follow `parentRunId` | Cannot answer "has this task definition ever succeeded?" |

### 2. Scope

**In scope**: runs, tasks, dependency edges, daemon instances, the cross-run reuse index, schema version and migrations.

**Out of scope**: log bodies (they stay files), artifact cache contents, multi-machine distribution, authentication and user management.

### 3. Module and dependencies

A new **`devpulse-storage`** module:

- Exposes only a `RunStore` interface and data records, with no business logic
- Contains the JDBC implementation and schema migrations
- `devpulse-core` depends on it; `RunHistory` becomes a thin wrapper with a mostly unchanged signature
- A future web client can depend on it directly

The engine keeps its role while storage becomes a reusable, independently testable boundary. Core modules go from three to four (core, storage, pulse, dist), which is the structural cost of platform work.

### 4. Storage engine

| | **H2 (recommended)** | SQLite |
|---|---|---|
| Size | About 2.5 MB, pure Java | About 12 MB with per-platform native libraries |
| Runtime | No native dependency | Extracts native libraries to a temporary directory, which can fail in restricted environments |
| Human inspection | Through JDBC | A universal `sqlite3` command |
| Future server | Can run in server mode | Single-writer model |

**H2 is recommended**: pure Java matches the "single jar, no runtime" positioning and avoids native extraction failures on Windows. The cost is losing the `sqlite3` convenience, covered by the JSON views of `status` rather than by a new command.

**Constraint**: the engine must stay replaceable. `RunStore` is a stable interface, and SQL appears only in implementations and migration scripts.

### 5. Data model

```text
run(id PK, project, target, parallelism, state, executor_pid,
    started_at, finished_at, bash, tool_version, parent_run_id)

task(run_id FK, name, fingerprint, state, exit_code, error,
     started_at, finished_at, log_path, reused_from,
     daemon_health, daemon_error, daemon_checked_at,
     PRIMARY KEY (run_id, name))

task_need(run_id, task_name, need_name,
          PRIMARY KEY (run_id, task_name, need_name))

daemon_instance(run_id, task_name, instance_id, attempt, pid, pid_started_at,
                started_at, ready_at, stopped_at, stop_command,
                PRIMARY KEY (run_id, task_name, instance_id))

task_result(name, fingerprint, run_id, finished_at,
            PRIMARY KEY (name, fingerprint))

schema_version(version, applied_at)
```

Design notes:

- **`task_need` is its own table** rather than a serialized column: a web client must query and render dependencies, and a serialized field cannot be queried.
- **`task_result` is the cross-run reuse index** and the replacement for retry: a key that exists with a successful result can be skipped. It is keyed by `(name, fingerprint)` and bound to no parent run.
- **Log bodies stay out of the database**; `log_path` stores a relative path. Logs are streaming, potentially large, and need tail semantics.
- **`daemon_instance` keeps `pid` and `pid_started_at`**: process identity verification uses exactly these two fields, matching the current `Owner` logic.

Indexes: `run(project, started_at DESC)`, `task(run_id)`, `task_result(name)`.

### 6. Concurrency and consistency

- One state change is one `UPDATE` instead of a whole-record rewrite.
- Executor exclusion uses `run.executor_pid` plus a liveness check instead of the `run.lock` file, while still taking a database write lock so two processes cannot start the same run.
- Writes are idempotent: `MERGE` (H2) rather than `INSERT`, so a replay cannot duplicate rows.
- The `run` row and its initial `task` rows are created in one transaction, so there is no "run without tasks" intermediate state.

### 7. Legacy data

**No migration.** The existing `.devpulse/runs/` directory is left in place but no longer read, and the documentation says it can be deleted. There is a single user today, and migration code would itself become a long-lived compatibility burden.

### 8. Effect on retry

The set computation in `RetryPlan` becomes one query:

```sql
SELECT run_id, finished_at FROM task_result
WHERE name = ? AND fingerprint = ? AND finished_at IS NOT NULL
```

Therefore:

- The `retry` command and `RetryPlan` are **deleted**
- "Skip completed nodes" becomes a switch on `pulse` (`--cached` / `--no-cached`, defaulting to off so no behavior changes silently)
- The "what would rerun" report `retry --dry-run` provided moves into `pulse --dry-run --format json`, backed by the same query

### 9. Support for a future web client

This model answers directly:

- The last N runs of a project and their states
- Historical duration distribution and failure rate per task
- Daemon time from start to readiness
- Cross-run reuse hit rate

None of these need new tables. Real-time streaming, if ever needed, gets its own design rather than being pre-wired into the schema.

### 10. Risks

| Risk | Response |
|---|---|
| About 2.5 MB added to the jar | Acceptable; `pulse` is 3.3 MB today |
| Losing direct `cat state.conf` inspection | `status --format json` covers nearly all of it |
| Schema evolution | Ship `schema_version` and ordered migrations from the first version; never "change a table and the code together" |
| Concurrent writes to a single-file database | State the single-writer constraint; revisit server mode when a web client writes |
| JDBC inside core | Isolated behind `devpulse-storage`; core depends only on the interface |

### 11. Stages

| Stage | Content | Acceptance |
|---|---|---|
| 1 | `devpulse-storage` module, schema, migration skeleton, `RunStore` with the H2 implementation | Interface tests pass; not wired into production paths |
| 2 | `RunHistory` reimplemented over the store with **zero CLI behavior change** | Every existing test passes; no new behavior |
| 3 | `task_result` wired in, `pulse` can skip completed nodes, `retry`/`RetryPlan` removed | Cross-run reuse works; retry tests are removed |
| 4 | A web client reuses the same storage | Later |

Stages 1 and 2 change no user-visible behavior; they are internal replacement and therefore the lowest risk.

### 12. Decisions needed

1. Engine: H2 (recommended) or SQLite
2. Logs: stay files (recommended) or go into the database
3. Add `owner` / `tenant` columns now? Only needed for multi-user or multi-host; recommended to skip
4. Legacy runs: no migration, no reads (recommended)
