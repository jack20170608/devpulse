# 客户端 / 服务端模型与 v3 存储设计 / Client-server model and v3 storage design

**状态 / Status**：提案，未实现 / Proposal, not implemented

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

## 中文

### 1. 定位

| 组件 | 角色 |
|------|------|
| `devpulse` CLI | 瘦客户端：提交运行、查询、订阅日志、取消。**不执行 bash，不访问数据库** |
| `devpulse-server` | 本地常驻进程：唯一执行者与唯一写入者，持有中央数据库，向所有客户端广播事件 |
| Web UI | 第三种客户端，走同一套 HTTP/SSE |

与当前实现的关键差异：今天的 `pulse` 在 CLI 进程内 `new WorkflowRun(...)` 执行并直接写库。改成服务端后，**客户端断开不影响运行**，这是这次拆分唯一不可替代的价值。

### 2. 已定决策

1. **单库**：一个中央数据库文件（如 `~/.devpulse/server.mv.db`）承载所有项目的数据，`project` 是一等实体。
2. **单写者**：只有 server 写库；CLI 与 Web 只读事件流与查询接口。
3. **大文件留在项目目录**：`runs/<id>/` 下的日志与 `plan.conf` 不搬，库里只存路径。

单库的直接收益：跨项目分页与统计是一条 SQL，连接池只需一个，同时写库的锁竞争消失。

### 3. 目标模型

#### 3.1 `project`

```sql
CREATE TABLE project (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    path         VARCHAR(1024) NOT NULL,
    name         VARCHAR(256) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT project_path_unique UNIQUE (path)
);
```

`path` 必须来自 `toRealPath()`。字符串路径不是身份：软链、Windows 大小写都会让同一个项目变成两个。

#### 3.2 `run`

```sql
CREATE TABLE run (
    id                VARCHAR(64)  NOT NULL PRIMARY KEY,
    project_id        VARCHAR(64)  NOT NULL,
    target            VARCHAR(256) NOT NULL,
    parallelism       INT          NOT NULL,
    state             VARCHAR(32)  NOT NULL,
    requested_by      VARCHAR(256),
    queued_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE,
    finished_at       TIMESTAMP WITH TIME ZONE,
    worker_pid        BIGINT,
    worker_started_at TIMESTAMP WITH TIME ZONE,
    server_boot_id    VARCHAR(64),
    bash              VARCHAR(1024),
    tool_version      VARCHAR(128),
    parent_run_id     VARCHAR(64),
    CONSTRAINT run_project FOREIGN KEY (project_id)
        REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT run_state CHECK (state IN
        ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED', 'INTERRUPTED'))
);
```

三个语义变化：

- `queued_at` 与 `started_at` 分离：排队时长可观测，`QUEUED` 是一等状态。
- `requested_by`：谁提交的（`user@host` 加工作目录），审计需要。
- `executor_pid` 改为 `worker_pid` + `server_boot_id`：server 重启后能识别遗留的执行进程。

#### 3.3 `task`

```sql
CREATE TABLE task (
    run_id      VARCHAR(64)  NOT NULL,
    name        VARCHAR(256) NOT NULL,
    task_id     VARCHAR(64)  NOT NULL,
    seq         INT          NOT NULL,
    fingerprint VARCHAR(128),
    state       VARCHAR(32)  NOT NULL,
    exit_code   INT,
    error       VARCHAR(4096),
    error_kind  VARCHAR(32),
    started_at  TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    log_path    VARCHAR(1024),
    reused_from VARCHAR(64),
    PRIMARY KEY (run_id, name),
    CONSTRAINT task_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT task_id_unique UNIQUE (run_id, task_id),
    CONSTRAINT task_state CHECK (state IN
        ('PENDING', 'RUNNING', 'RETRYING', 'SUCCESS',
         'FAILED', 'TIMED_OUT', 'SKIPPED', 'CANCELLED'))
);
```

- `daemon_health` / `daemon_error` / `daemon_checked_at` 移入 `daemon_instance`：一张表不再有两套实体。
- 新增 `error_kind`：区分"进程退出码失败"与"适配器异常"，不必再从 `error` 文本猜。

#### 3.4 `task_edge`（原 `task_need`）

```sql
CREATE TABLE task_edge (
    run_id            VARCHAR(64)  NOT NULL,
    dependent_name    VARCHAR(256) NOT NULL,
    prerequisite_name VARCHAR(256) NOT NULL,
    seq               INT          NOT NULL,
    PRIMARY KEY (run_id, dependent_name, prerequisite_name),
    CONSTRAINT edge_dependent FOREIGN KEY (run_id, dependent_name)
        REFERENCES task (run_id, name) ON DELETE CASCADE,
    CONSTRAINT edge_prerequisite FOREIGN KEY (run_id, prerequisite_name)
        REFERENCES task (run_id, name) ON DELETE CASCADE
);
```

改名理由：`need` 是自然语言，`edge` 是图语言。同时**补上缺失的外键**——当前结构只约束了依赖方，被依赖方可以是任意字符串。

方向写进列名：`dependent` 依赖 `prerequisite`，不再需要看文档猜。

#### 3.5 `task_result`

```sql
CREATE TABLE task_result (
    project_id  VARCHAR(64)  NOT NULL,
    name        VARCHAR(256) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    run_id      VARCHAR(64),
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, name, fingerprint),
    CONSTRAINT result_project FOREIGN KEY (project_id)
        REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT result_run FOREIGN KEY (run_id)
        REFERENCES run (id) ON DELETE SET NULL
);
```

主键加 `project_id`：跨项目复用必须禁止。同名同指纹的任务在两个项目里语义不同。

#### 3.6 `daemon_instance`

```sql
CREATE TABLE daemon_instance (
    run_id             VARCHAR(64) NOT NULL,
    task_id            VARCHAR(64) NOT NULL,
    instance_id        VARCHAR(64) NOT NULL,
    attempt            INT         NOT NULL,
    name               VARCHAR(256) NOT NULL,
    pid                BIGINT      NOT NULL,
    process_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ready_command      VARCHAR(4096) NOT NULL,
    stop_command       VARCHAR(4096),
    stop_timeout_nanos BIGINT      NOT NULL,
    interval_nanos     BIGINT      NOT NULL,
    timeout_nanos      BIGINT      NOT NULL,
    check_timeout_nanos BIGINT     NOT NULL,
    health             VARCHAR(32),
    health_error       VARCHAR(4096),
    checked_at         TIMESTAMP WITH TIME ZONE,
    ready_at           TIMESTAMP WITH TIME ZONE,
    stopped_at         TIMESTAMP WITH TIME ZONE,
    server_boot_id     VARCHAR(64),
    PRIMARY KEY (run_id, task_id, instance_id),
    CONSTRAINT daemon_run FOREIGN KEY (run_id)
        REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT daemon_task FOREIGN KEY (run_id, task_id)
        REFERENCES task (run_id, task_id) ON DELETE CASCADE,
    CONSTRAINT daemon_instance_unique UNIQUE (run_id, instance_id)
);
```

补上 `(run_id, task_id)` 外键——当前这里是完整性漏洞；删掉重复的 `project` 与 `bash` 列（分别由 `run` 与 server 提供）。

#### 3.7 `run_event`（新增）

```sql
CREATE TABLE run_event (
    run_id  VARCHAR(64) NOT NULL,
    seq     BIGINT      NOT NULL,
    type    VARCHAR(64) NOT NULL,
    payload VARCHAR(8192),
    at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (run_id, seq),
    CONSTRAINT event_run FOREIGN KEY (run_id)
        REFERENCES run (id) ON DELETE CASCADE
);
```

没有这张表，"客户端掉线、运行继续"就无法兑现：客户端回来时看不到中间发生了什么。`seq` 作为游标，对应 SSE 的 `Last-Event-ID`。

### 4. 与 v2 的对照

| 变更 | v2 | v3 |
|------|----|----|
| 项目 | `run.project` 字符串，每项目一库 | `project` 表，`run.project_id` 外键 |
| 运行起点 | 创建即 `started_at` | `queued_at` + `QUEUED` → `started_at` |
| 执行者 | `executor_pid` | `worker_pid` + `server_boot_id` |
| 依赖边 | `task_need(run_id, task_name, need_name)` | `task_edge(run_id, dependent, prerequisite)` + 双向外键 |
| 复用索引 | `(name, fingerprint)` | `(project_id, name, fingerprint)` |
| 事件 | 进程内回调 | `run_event` 持久化 + 游标 |
| daemon 健康 | 混在 `task` | 移入 `daemon_instance` |
| 约束 | 无 CHECK | 状态列带 CHECK |

### 5. 迁移策略

单库意味着与现有"每项目一库"不是同一个库，因此**不做原地升级**：

1. v3 迁移只服务**新建的中央库**。对新库按 v1 → v2 → v3 顺序执行（v3 内 `DROP` 旧表并建新表），或用一条新的基线，视实现成本决定。
2. 存量数据由显式工具导入：`devpulse-server migrate --from <project-dir>`，读取旧的 `state.mv.db`，写入中央库，并补齐 `project_id`。
3. v3 迁移在 Java 侧加守卫：若旧表仍有数据则拒绝执行并提示先运行导入工具，避免静默丢数据。

### 6. 客户端接口草案

| 能力 | 形式 |
|------|------|
| 提交 | `POST /api/runs`（project、target、overrides），返回 run id |
| 查询 | `GET /api/runs?project=&state=&limit=&offset=`，复用 `RunQueries`/`RunSummary` |
| 详情 | `GET /api/runs/{id}` |
| 事件 | `GET /api/runs/{id}/events?after=<seq>`（SSE，断线按 `seq` 续读） |
| 日志 | `GET /api/runs/{id}/logs/{task}?after=<offset>` |
| 取消 | `POST /api/runs/{id}/cancel` |
| 守护进程 | `GET /api/daemons`、`POST /api/daemons/{instance}/stop` |

传输走 loopback HTTP，配 `~/.devpulse/token` 本地认证。暂不引入 gRPC。

### 7. 未决

1. server 不在时 CLI 的行为：自动拉起（Docker 风格）、直接报错、还是本地回退。推荐自动拉起，并把本地回退限定在一个过渡版本内。
2. 本地模式（CLI 直接执行）是否保留。保留意味着两套执行路径，长期必然分叉。
3. server 退出时是否自动停止 daemon。当前语义是"永不自动停止，由用户执行 stop"。

## English

### 1. Positioning

| Component | Role |
|-----------|------|
| `devpulse` CLI | Thin client: submit runs, query, follow logs, cancel. **Runs no bash and touches no database** |
| `devpulse-server` | Long-running local process: the only executor and the only writer, owns the central database, broadcasts events to every client |
| Web UI | A third client over the same HTTP/SSE surface |

The one capability this split buys that nothing else does: a client can disconnect and the run keeps going.

### 2. Decisions taken

1. **Single database**: one central file holds every project; `project` is a first-class entity.
2. **Single writer**: only the server writes; clients read through queries and the event stream.
3. **Large files stay in the project directory**: logs and `plan.conf` under `runs/<id>/` do not move; the database stores their paths.

The payoff: cross-project paging and statistics are one SQL statement, one pool suffices, and write contention disappears.

### 3. Target model

**`project`** — id, canonical path (`toRealPath`), name, timestamps; unique path. A path string is not an identity: symlinks and Windows case folding split one project into two.

**`run`** — adds `project_id` (foreign key), `requested_by`, separates `queued_at` from `started_at`, makes `QUEUED` a real state, and replaces `executor_pid` with `worker_pid` plus `server_boot_id` so a restarted server can identify orphaned work. State gets a CHECK constraint.

**`task`** — drops the three daemon health columns (they move to `daemon_instance`) and adds `error_kind`, so "process exited non-zero" and "adapter threw" stop looking alike.

**`task_edge`** (was `task_need`) — renames the concept and names the direction: `dependent_name` needs `prerequisite_name`. Both columns get foreign keys; today only the dependent side is constrained.

**`task_result`** — primary key becomes `(project_id, name, fingerprint)`. Reuse across projects must be impossible: the same task name and fingerprint mean different things in different projects.

**`daemon_instance`** — gains the `(run_id, task_id)` foreign key that is missing today, and drops the duplicated `project` and `bash` columns.

**`run_event`** (new) — `(run_id, seq, type, payload, at)`. Without it, a reconnecting client cannot see what happened while it was away. `seq` is the cursor, matching SSE's `Last-Event-ID`.

### 4. Migration

A single database is not an upgrade of the per-project store, so there is no in-place migration:

1. The v3 migration only serves a **new central store**.
2. Existing data is imported explicitly: `devpulse-server migrate --from <project-dir>` reads the old `state.mv.db`, writes the central tables, and fills in `project_id`.
3. A Java-side guard refuses v3 while old tables still hold rows, so data cannot be dropped silently.

### 5. Client surface

Submit, query, detail, events (SSE with a `seq` cursor), logs, cancel, daemons. Loopback HTTP with a local token; no gRPC yet.

### 6. Open questions

1. What the CLI does when the server is absent: auto-start, fail, or local fallback. Auto-start is preferred, with local fallback limited to one transition release.
2. Whether the local execution mode survives. Keeping it means two execution paths, which drift over time.
3. Whether the server stops daemons on exit; the current semantics never stop them automatically.
