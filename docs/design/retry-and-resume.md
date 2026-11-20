# Retry 与恢复设计 / Retry and resume design

**状态 / Status**：提案，未实现 / Proposal, not implemented

[项目首页](../readme.MD)

## 中文

### 1. 问题

`pulse` 目前只有"整条流水线重跑"这一种失败处理。一次 12 个任务的流水线在最后一个任务失败后，用户或 agent 只能重跑全部。`devpulse-core/README.md` 已把"后续批任务重试/超时配置"和"通用 DAG 持久化恢复"列为路线，本文给出其中"节点级恢复"的设计。

**目标**：只重跑受影响的节点，复用已成功节点，并复用仍在运行且已就绪的 daemon。

**非目标**：跨机器恢复、任意断点续跑、产物级缓存。这些属于 Temporal 与 Nextflow 的领域，本设计不涉及。

### 2. 现有持久化模型（事实）

每次执行在 `<项目>/.devpulse/runs/<run-id>/` 下记录：

| 文件 | 内容 |
|------|------|
| `state.conf` | `version: 1`、`id`、`target`、`project`、`parallelism`、`executorPid`、`executorStartedAt`、`startedAt`、`finishedAt`、`state`、`tasks` |
| `tasks[]` 每项 | `id`、`name`、`needs`、`state`、`logPath`、`startedAt`、`finishedAt`、`exitCode`、`error`、`daemonHealth`、`daemonError`、`daemonCheckedAt` |
| `daemons/<taskId>.conf`，或 `daemons/<taskId>/<instanceId>.conf` | 进程身份、`attempt`、就绪时间、停止超时等 |
| `run.lock` | 单执行者锁 |

三个关键事实决定了设计空间：

1. **任务 id 每次运行重新生成**，`name` 稳定。跨运行只认 `name`。
2. **daemon 实例记录隶属于运行目录**，按 `taskId` 分目录。跨运行复用必须显式接管记录。
3. **没有记录配置内容、配置哈希、Bash 路径或父运行 id**。因此当前无法证明"重试时配置没变"。`RunHistory.read` 只接受 `version == 1`。
4. 尝试级管道已存在：`TaskContext.attempt()`、`BashTaskAdapter` 的 `> 1` 日志追加、`DaemonManager` 的 `attempt`。**但没有策略驱动它**，当前 `attempt` 恒为 1。

### 3. 核心语义

`pulse retry <run-id>` 创建**一次新运行**，并在其中复用父运行的部分结果：

| 集合 | 定义 |
|------|------|
| 重跑集合 | 父运行中 `FAILED`、`TIMED_OUT`、`CANCELLED`、`SKIPPED` 的节点，加上它们的**全部后继** |
| 复用集合 | 父运行中 `SUCCESS` 且不在重跑集合中的节点 |
| daemon 指标 | 复用的 daemon 节点沿用父实例；失效或消失的 daemon 连同其后继进入重跑集合 |

沿用现有政策：**重试结束不停止任何 daemon**，由用户通过 `pulse stop` 管理。

### 4. 可靠性前提

复用成功节点的唯一前提是"输入没有变化"。输入有三类：

| 输入 | 能否廉价验证 | 处理 |
|------|--------------|------|
| 配置（`cmd`、`needs`、`type`、`ready`、`stopTimeout`…） | 能 | 记录规范化哈希，重试时比对 |
| Bash 可执行文件 | 能 | 记录路径与版本输出 |
| 工作区文件内容 | **不能** | 由用户负责 |

因此重试只保证前两类。**如果用户改了源码，先前成功的节点结果可能已失效**——这一点必须写进 `--help` 和错误提示，不能靠用户猜。提供 `--from-scratch` 放弃复用来回避该风险。

### 5. 数据格式 v2

`state.conf` 升到 `version: 2`，新增：

| 位置 | 字段 | 说明 |
|------|------|------|
| 运行级 | `configHash` | 规范化配置（`version`、`parallelism`、`tasks`、`pipelines`）的 SHA-256 |
| 运行级 | `bash` | 执行所用 Bash 的绝对路径 |
| 运行级 | `parentRunId` | 重试时指向父运行，首次执行为空 |
| `tasks[]` | `reusedFrom` | 该节点从哪个运行的哪个 task id 复用时写入 |

**兼容**：`RunHistory.read` 接受 `version` 为 `1` 或 `2`。`retry` 对 v1 运行**拒绝执行**（缺少 `configHash`，无法证明配置未变），返回 `13`。

### 6. CLI 契约

```text
pulse retry <RUN_ID> [--directory <目录>] [--bash <路径>] [--jobs <N>]
                     [--dry-run] [--force] [--from-scratch] [--format text|json]
```

| 场景 | 退出码 |
|------|--------|
| 重试运行全部成功 | `0` |
| 重试运行存在失败节点 | `30` / `31` / `32`（沿用现有分类） |
| 运行 ID 不存在 | `12` |
| **拒绝重试**：父运行是 v1、配置哈希不符、父运行 `PENDING`/`RUNNING` 且未加 `--force` | **`13`** |

`--dry-run` 打印将重跑与将复用的节点清单，不执行、不创建运行记录。`--format json` 输出：

```json
{"schemaVersion":1,"command":"retry","ok":true,"dryRun":true,
 "parentRunId":"…","rerun":["build","test"],"reuse":["lint"],
 "reusedDaemons":["server"],"configChanged":false}
```

### 7. 分三阶段落地

| 阶段 | 内容 | 风险 |
|------|------|------|
| 1 | 写入 v2 字段；实现 `retry --dry-run`（只报告，不执行） | 低：只新增字段与只读命令 |
| 2 | 实现按集合执行与复用成功节点 | 中：改变执行语义 |
| 3 | 实现 daemon 接管（复制实例记录到新运行目录，标记来源） | 高：涉及进程所有权与 `stop` 边界 |

每阶段都必须在 `bash verify.sh` 下通过，并保持 v1 记录可读。

### 8. 可测性

核心逻辑必须是纯函数，便于测试：

```text
rerunSet(plan, previousTaskStates) -> Set<taskName>
```

由它决定重跑集合，测试只需构造计划与父状态，无需启动进程。daemon 接管属于阶段 3，需要真实进程验证。

### 9. 未决问题

1. `parallelism` 变化是否允许？当前设计计入 `configHash`，因此会拒绝重试；可考虑把并行度排除在哈希之外。
2. 复用节点在新运行中的 `startedAt`/`finishedAt` 如何呈现：沿用父值，还是留空并只在 `reusedFrom` 中体现？
3. 是否允许对 `RUNNING` 的父运行重试？当前设计默认拒绝，`--force` 仅覆盖此情形。
4. 两个运行同时持有同一 daemon 时，`pulse stop` 的作用域如何界定？
5. 配置哈希的规范化口径：是否包含 `description` 与 `pipelines` 这类不影响执行的字段？

### 10. 验收标准

- 12 个节点的菱形流水线，最后 1 个失败；修复后 `retry` 只执行该节点，其余 11 个标记为复用。
- 配置在失败后变更：`retry` 返回 `13` 并说明哪个字段变了；`--force` 可继续。
- daemon 已就绪场景：`retry` 不重启 daemon，且下游任务照常执行。
- 父运行为 v1：`retry` 返回 `13` 并提示"该运行早于可重试格式"。
- `retry --dry-run --format json` 的输出可被程序直接消费，不需要读日志。

## English

### 1. Problem

`pulse` only knows how to rerun an entire pipeline. After a task late in a pipeline fails, a user or agent must rerun everything. `devpulse-core/README.md` already lists "batch task retry and timeout configuration" and "general DAG persistence and recovery" as roadmap items; this document designs the node-level resume part.

**Goal**: rerun only the affected nodes, reuse already-successful nodes, and reuse daemons that are still running and ready.

**Non-goals**: cross-machine recovery, arbitrary checkpoint resume, and artifact-level caching. Those belong to Temporal and Nextflow and are out of scope.

### 2. Current persistence model (facts)

Each execution records under `<project>/.devpulse/runs/<run-id>/`:

| File | Contents |
|------|----------|
| `state.conf` | `version: 1`, `id`, `target`, `project`, `parallelism`, `executorPid`, `executorStartedAt`, `startedAt`, `finishedAt`, `state`, `tasks` |
| each `tasks[]` entry | `id`, `name`, `needs`, `state`, `logPath`, `startedAt`, `finishedAt`, `exitCode`, `error`, `daemonHealth`, `daemonError`, `daemonCheckedAt` |
| `daemons/<taskId>.conf`, or `daemons/<taskId>/<instanceId>.conf` | process identity, `attempt`, readiness time, stop timeout |
| `run.lock` | single-executor lock |

Four facts constrain the design:

1. **Task ids are regenerated per run**; `name` is stable. Only `name` can be matched across runs.
2. **Daemon instance records belong to the run directory**, keyed by `taskId`. Cross-run reuse must adopt those records explicitly.
3. **Nothing records configuration content, a configuration hash, the Bash path, or a parent run id.** A retry therefore cannot prove the configuration is unchanged. `RunHistory.read` accepts only `version == 1`.
4. Attempt plumbing already exists: `TaskContext.attempt()`, `BashTaskAdapter`'s log append for `> 1`, and `DaemonManager`'s `attempt`. **No policy drives it**, so `attempt` is always 1 today.

### 3. Core semantics

`pulse retry <run-id>` creates **a new run** that reuses part of its parent's results:

| Set | Definition |
|-----|------------|
| Rerun | nodes that were `FAILED`, `TIMED_OUT`, `CANCELLED`, or `SKIPPED`, plus **all their descendants** |
| Reuse | nodes that were `SUCCESS` and are not in the rerun set |
| Daemons | reused daemon nodes keep their parent instance; a daemon that is gone puts itself and its descendants into the rerun set |

The existing policy stands: **a retry never stops daemons**; the user manages them with `pulse stop`.

### 4. Soundness preconditions

Reusing a successful node is valid only if its inputs are unchanged. There are three kinds of input:

| Input | Cheap to verify | Handling |
|-------|-----------------|----------|
| Configuration (`cmd`, `needs`, `type`, `ready`, `stopTimeout`, …) | Yes | Record a normalized hash and compare on retry |
| The Bash executable | Yes | Record its path and version output |
| Working-tree contents | **No** | The user's responsibility |

A retry therefore guarantees only the first two. **If source files changed, earlier successes may be stale**, and that must be stated in `--help` and in error messages rather than left for the user to infer. `--from-scratch` exists to avoid the risk entirely.

### 5. Data format v2

`state.conf` moves to `version: 2` with these additions:

| Scope | Field | Purpose |
|-------|-------|---------|
| Run | `configHash` | SHA-256 of the normalized configuration (`version`, `parallelism`, `tasks`, `pipelines`) |
| Run | `bash` | Absolute path of the Bash used |
| Run | `parentRunId` | Points at the parent run for a retry; empty for a first execution |
| `tasks[]` | `reusedFrom` | Set when the node was reused, naming the source run and task id |

**Compatibility**: `RunHistory.read` accepts `version` `1` or `2`. `retry` **refuses** a v1 run (no `configHash`, so the configuration cannot be proven unchanged) and returns `13`.

### 6. CLI contract

```text
pulse retry <RUN_ID> [--directory <directory>] [--bash <path>] [--jobs <N>]
                     [--dry-run] [--force] [--from-scratch] [--format text|json]
```

| Situation | Exit code |
|-----------|-----------|
| The retry run succeeds | `0` |
| The retry run has failing nodes | `30` / `31` / `32`, using the existing taxonomy |
| Unknown run id | `12` |
| **Refused**: v1 parent, configuration hash mismatch, or a `PENDING`/`RUNNING` parent without `--force` | **`13`** |

`--dry-run` prints the rerun and reuse sets without executing or creating a run. `--format json` emits:

```json
{"schemaVersion":1,"command":"retry","ok":true,"dryRun":true,
 "parentRunId":"…","rerun":["build","test"],"reuse":["lint"],
 "reusedDaemons":["server"],"configChanged":false}
```

### 7. Three delivery stages

| Stage | Content | Risk |
|-------|---------|------|
| 1 | Write the v2 fields; implement `retry --dry-run` (report only) | Low: new fields plus a read-only command |
| 2 | Execute by set and reuse successful nodes | Medium: changes execution semantics |
| 3 | Adopt daemons (copy instance records into the new run, mark their origin) | High: process ownership and `stop` boundaries |

Every stage must pass `bash verify.sh` and keep v1 records readable.

### 8. Testability

The core decision must be a pure function:

```text
rerunSet(plan, previousTaskStates) -> Set<taskName>
```

Tests then construct a plan and parent states without spawning processes. Daemon adoption is stage 3 and needs real processes to verify.

### 9. Open questions

1. Should a `parallelism` change be allowed? As designed it is part of `configHash` and therefore refuses a retry; excluding concurrency from the hash is a reasonable alternative.
2. How should a reused node present `startedAt`/`finishedAt` in the new run: keep the parent values, or leave them empty and rely on `reusedFrom`?
3. Should a `RUNNING` parent be retryable? This design refuses by default, with `--force` covering only that case.
4. When two runs hold the same daemon, what is the scope of `pulse stop`?
5. What exactly belongs in the configuration hash — should non-execution fields such as `description` and `pipelines` be included?

### 10. Acceptance criteria

- A diamond pipeline of 12 nodes whose last node fails reruns only that node after a fix, marking the other 11 as reused.
- If the configuration changed after the failure, `retry` returns `13` and names the changed field; `--force` proceeds.
- With a ready daemon, `retry` does not restart it and downstream tasks still run.
- A v1 parent returns `13` with "this run predates the retryable format".
- `retry --dry-run --format json` is directly consumable, with no log reading required.
