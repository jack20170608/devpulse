# Retry 与恢复设计 / Retry and resume design

**状态 / Status**：提案，未实现 / Proposal, not implemented

[项目首页](../readme.MD)

## 中文

### 1. 问题与目标

`pulse` 目前只有"整条流水线重跑"这一种失败处理。**目标**：只重跑受影响的节点，复用已成功节点，复用仍在运行且已就绪的 daemon。**非目标**：跨机器恢复、任意断点续跑、产物级缓存——那是 Temporal 与 Nextflow 的领域。

### 2. 现有约束（事实）

每次执行在 `<项目>/.devpulse/runs/<run-id>/` 下记录 `state.conf`（运行级：`id`、`target`、`project`、`parallelism`、`executorPid`、`startedAt`、`finishedAt`、`state`，以及 `tasks[]` 每项的 `id`、`name`、`needs`、`state`、`logPath`、时间戳、`exitCode`、`error`、`daemonHealth`／`daemonError`／`daemonCheckedAt`）、`daemons/<taskId>.conf` 或 `daemons/<taskId>/<instanceId>.conf`（进程身份、`attempt`、就绪时间、停止超时）与 `run.lock`。

三个事实限定设计空间：

1. **任务 id 每次运行重新生成，`name` 稳定**。跨运行只认 `name`。
2. **daemon 实例记录隶属于运行目录**，按 `taskId` 分目录；跨运行复用必须显式接管。
3. **当前不记录任何配置内容或哈希**。因此无法判断"哪些节点受影响"。
4. 尝试级管道已存在（`TaskContext.attempt()`、`BashTaskAdapter` 的 `attempt > 1` 日志追加、`DaemonManager.attempt`），但**没有策略驱动**，`attempt` 恒为 1。

### 3. 设计原则

| 原则 | 含义 |
|------|------|
| **精确失效，不做全局熔断** | 用逐节点指纹决定哪些节点重跑，而不是"配置一变就整条重来" |
| **不制造第二个真相** | 复用节点不复制父运行的时间戳与日志；用 `reusedFrom` 单向指向证据 |
| **只在能证明时才断言** | 无法验证的（工作区文件）显式声明为用户责任，而不是假装验证过 |
| **失败要响亮** | 不可复用、不可接管、状态不明时返回明确退出码，不静默降级 |

### 4. 复用判据

`pulse retry <run-id>` 创建**一次新运行**，新运行 id，`parentRunId` 指向父运行。

节点进入**重跑集合**，当且仅当满足任一条件：

1. 父运行中该节点状态不是 `SUCCESS`；
2. 该节点的**执行指纹**与当前配置不一致（见 §5）；
3. 该节点是 daemon，且父实例无法安全接管（身份不匹配或已不存活）；
4. 它是上述任一节点的**后继**（传递闭包）。

其余节点进入**复用集合**。新增节点（父运行中不存在）按条件 2 处理。被删除的节点不出现在新计划中，无需处理。

沿用现有政策：**重试结束不停止任何 daemon**，由 `pulse stop` 管理。

### 5. 执行指纹

指纹是**逐节点**的，覆盖一切影响该节点执行结果的输入：

```text
fingerprint = SHA-256(
    type,            // task | daemon
    cmd,
    needs,           // 排序去重后
    ready.cmd, ready.interval, ready.timeout, ready.checkTimeout,
    stop, stopTimeout
)
```

**不进入指纹**：`description`、`pipelines`（不参与该节点执行）、`parallelism`（只影响调度并发，不影响单节点结果）。

运行级另存：`bash`（绝对路径 + 版本输出）、`project`、`target`、`parallelism`、`pulseVersion`，以及**规范化后的完整计划**（`name` → `fingerprint`、`needs`），用于重试时逐节点比对与诊断输出。

### 6. 数据格式

全新格式，`state.conf` 为 `version: 2`，**读取时只接受 `2`**；其他值直接报错，不做迁移。

| 位置 | 字段 | 说明 |
|------|------|------|
| 运行级 | `version` | 固定 `2` |
| 运行级 | `parentRunId` | 重试时指向父运行；首次执行为空 |
| 运行级 | `bash` | 执行所用 Bash 的绝对路径与版本 |
| 运行级 | `plan` | 规范化计划：每个节点的 `name`、`fingerprint`、`needs` |
| `tasks[]` | `fingerprint` | 该节点执行时的指纹 |
| `tasks[]` | `reusedFrom` | 复用时写入 `"<父运行 id>/<父 task id>"`，否则为空 |

`state.conf` 采用**原子写入**（同目录临时文件 + 重命名），避免中断留下半截记录。

### 7. CLI 契约

```text
pulse retry <RUN_ID> [--directory <目录>] [--bash <路径>] [--jobs <N>]
                     [--dry-run] [--force] [--from-scratch] [--format text|json]
```

| 选项 | 作用 |
|------|------|
| `--dry-run` | 只输出重跑／复用集合，不执行、不创建运行记录 |
| `--from-scratch` | 忽略所有复用，等同于新执行，但仍记录 `parentRunId` |
| `--force` | 允许对 `PENDING`／`RUNNING` 的父运行重试；需要同时确认无存活执行者 |
| `--jobs` | 覆盖并发上限；不影响指纹 |

退出码沿用现有分类，新增一个：

| 场景 | 退出码 |
|------|--------|
| 重试全部成功 | `0` |
| 重试存在失败节点 | `30` / `31` / `32` |
| 运行 id 不存在，或父运行目标在当前配置中已不可解析 | `12` |
| **拒绝重试**：父运行仍在执行、记录不可读、格式版本不符 | **`13`** |

`--format json`：

```json
{"schemaVersion":1,"command":"retry","ok":true,"dryRun":true,
 "parentRunId":"…","rerun":["build","test"],"reuse":["lint"],
 "rerunReasons":{"test":"parent FAILED"},"reusedDaemons":["server"],
 "bashChanged":false}
```

### 8. 无法验证的部分

复用成功节点的前提是"输入没有变化"。输入有三类：

| 输入 | 可验证 | 处理 |
|------|--------|------|
| 节点执行定义 | 是 | 指纹 |
| Bash 可执行文件 | 是 | 记录路径与版本并比对；变化时输出 `bashChanged` 并**保留复用**（指纹已覆盖命令，Bash 变化不构成失效理由），仅在文本输出中提示 |
| 工作区文件内容 | **否** | 由用户负责 |

因此：**改了源码之后，先前成功的节点可能已失效**。文本模式在重试开始时打印被复用节点清单并附一行提示；JSON 通过 `reuse` 字段明示。提供 `--from-scratch` 完全回避该风险。这一点必须写进 `--help`，不能靠用户猜。

### 9. 分三阶段落地

| 阶段 | 内容 | 风险 |
|------|------|------|
| 1 | 写入 v2 格式（含指纹与计划）＋ `retry --dry-run` | 低：只新增字段与只读命令 |
| 2 | 按集合执行，复用成功节点（日志与时间戳单向指向父运行） | 中：改变执行语义 |
| 3 | daemon 接管：身份校验通过则把父实例记录登记到新运行目录，标记来源 | 高：涉及进程所有权与 `stop` 边界 |

每阶段必须在 `bash verify.sh` 下通过。

### 10. 可测性

核心判定必须是纯函数，无需启动进程即可测试：

```text
retryPlan(plan, parentSnapshot, adoptableDaemons) -> RetryPlan { rerun, reuse, reasons }
```

daemon 接管属于阶段 3，需要真实进程验证。

### 11. 验收标准

- 12 节点菱形流水线最后 1 个失败；修复后 `retry` 只执行该节点，其余 11 个标记复用。
- 只修改某一个无关任务的 `description`：`retry` 仍复用全部成功节点（证明指纹口径正确）。
- 只修改某一个任务的 `cmd`：该节点与其全部后继重跑，其余复用。
- daemon 已就绪且身份匹配：`retry` 不重启 daemon，下游照常执行。
- daemon 已退出：该节点与其后继重跑。
- 父运行仍在执行：`retry` 返回 `13`，除非 `--force` 且确认无存活执行者。
- `retry --dry-run --format json` 可被程序直接消费，无需读日志。

## English

### 1. Problem and goal

`pulse` only knows how to rerun an entire pipeline. **Goal**: rerun only the affected nodes, reuse already-successful nodes, and reuse daemons that are still running and ready. **Non-goals**: cross-machine recovery, arbitrary checkpoint resume, and artifact-level caching — those belong to Temporal and Nextflow.

### 2. Current constraints (facts)

Each execution records `state.conf` under `<project>/.devpulse/runs/<run-id>/` (run level: `id`, `target`, `project`, `parallelism`, `executorPid`, `startedAt`, `finishedAt`, `state`, plus per-`tasks[]` entry `id`, `name`, `needs`, `state`, `logPath`, timestamps, `exitCode`, `error`, `daemonHealth`/`daemonError`/`daemonCheckedAt`), `daemons/<taskId>.conf` or `daemons/<taskId>/<instanceId>.conf` (process identity, `attempt`, readiness time, stop timeout), and `run.lock`.

Three facts constrain the design:

1. **Task ids are regenerated per run; `name` is stable.** Only `name` matches across runs.
2. **Daemon instance records belong to the run directory**, keyed by `taskId`; cross-run reuse must adopt them explicitly.
3. **Nothing records configuration content or a hash**, so there is no way to tell which nodes were affected.
4. Attempt plumbing already exists (`TaskContext.attempt()`, `BashTaskAdapter`'s log append for `attempt > 1`, `DaemonManager.attempt`) but **no policy drives it**; `attempt` is always 1.

### 3. Design principles

| Principle | Meaning |
|-----------|---------|
| **Precise invalidation, no global circuit breaker** | Per-node fingerprints decide what reruns, instead of "any config change reruns everything" |
| **No second truth** | A reused node does not copy the parent's timestamps or logs; `reusedFrom` points at the evidence one way |
| **Assert only what can be proven** | What cannot be verified (working-tree contents) is declared the user's responsibility rather than pretended to be checked |
| **Fail loudly** | Non-reusable nodes, unsafe adoption, and unknown states return explicit codes instead of degrading silently |

### 4. Reuse rule

`pulse retry <run-id>` creates **a new run** with a new id and `parentRunId` set to the parent.

A node joins the **rerun set** if and only if any of the following holds:

1. its state in the parent run is not `SUCCESS`;
2. its **execution fingerprint** differs from the current configuration (§5);
3. it is a daemon whose parent instance cannot be adopted safely (identity mismatch or no longer alive);
4. it is a **descendant** (transitive closure) of any node above.

Every other node joins the **reuse set**. A node absent from the parent is handled by rule 2. A node removed from the configuration simply does not appear in the new plan.

The existing policy stands: **a retry never stops daemons**; `pulse stop` manages them.

### 5. Execution fingerprint

The fingerprint is **per node** and covers every input that affects that node's result:

```text
fingerprint = SHA-256(
    type,            // task | daemon
    cmd,
    needs,           // sorted and deduplicated
    ready.cmd, ready.interval, ready.timeout, ready.checkTimeout,
    stop, stopTimeout
)
```

**Not in the fingerprint**: `description` and `pipelines` (they do not affect a node's execution) and `parallelism` (scheduling concurrency only, not a node's result).

The run additionally records `bash` (absolute path plus version output), `project`, `target`, `parallelism`, `pulseVersion`, and the **normalized plan** (`name` → `fingerprint`, `needs`) for per-node comparison and diagnostics.

### 6. Data format

A fresh format: `state.conf` is `version: 2`, and **reading accepts only `2`**; any other value fails with a clear error and no migration.

| Scope | Field | Purpose |
|-------|-------|---------|
| Run | `version` | Fixed at `2` |
| Run | `parentRunId` | Parent run for a retry; empty for a first execution |
| Run | `bash` | Absolute path and version of the Bash used |
| Run | `plan` | Normalized plan: each node's `name`, `fingerprint`, `needs` |
| `tasks[]` | `fingerprint` | The fingerprint this node executed with |
| `tasks[]` | `reusedFrom` | `"<parent run id>/<parent task id>"` when reused, otherwise empty |

`state.conf` is written **atomically** (temporary file in the same directory, then rename) so an interruption cannot leave a half-written record.

### 7. CLI contract

```text
pulse retry <RUN_ID> [--directory <directory>] [--bash <path>] [--jobs <N>]
                     [--dry-run] [--force] [--from-scratch] [--format text|json]
```

| Option | Effect |
|--------|--------|
| `--dry-run` | Report the rerun and reuse sets only; execute nothing and create no run |
| `--from-scratch` | Ignore all reuse, equivalent to a new execution, still recording `parentRunId` |
| `--force` | Allow retrying a `PENDING`/`RUNNING` parent; requires confirming no live executor |
| `--jobs` | Override the concurrency limit; does not affect fingerprints |

Exit codes reuse the existing taxonomy plus one addition:

| Situation | Exit code |
|-----------|-----------|
| The retry fully succeeds | `0` |
| The retry has failing nodes | `30` / `31` / `32` |
| Unknown run id, or the parent target no longer resolves | `12` |
| **Refused**: parent still executing, unreadable record, or wrong format version | **`13`** |

`--format json`:

```json
{"schemaVersion":1,"command":"retry","ok":true,"dryRun":true,
 "parentRunId":"…","rerun":["build","test"],"reuse":["lint"],
 "rerunReasons":{"test":"parent FAILED"},"reusedDaemons":["server"],
 "bashChanged":false}
```

### 8. What cannot be verified

Reusing a successful node assumes its inputs are unchanged. There are three kinds of input:

| Input | Verifiable | Handling |
|-------|------------|----------|
| The node's execution definition | Yes | Fingerprint |
| The Bash executable | Yes | Record path and version and compare; a change reports `bashChanged` but **keeps reuse**, since the fingerprint already covers the command; text output mentions it |
| Working-tree contents | **No** | The user's responsibility |

So **after changing source files, earlier successes may be stale**. Text mode prints the reused node list with a one-line warning when a retry starts; JSON states it through the `reuse` field. `--from-scratch` avoids the risk entirely. This belongs in `--help`, not in the user's inference.

### 9. Three delivery stages

| Stage | Content | Risk |
|-------|---------|------|
| 1 | Write the v2 format (fingerprints and plan) plus `retry --dry-run` | Low: new fields and a read-only command |
| 2 | Execute by set and reuse successful nodes (logs and timestamps point one way at the parent) | Medium: changes execution semantics |
| 3 | Daemon adoption: after identity verification, register the parent instance in the new run directory with its origin marked | High: process ownership and `stop` boundaries |

Every stage must pass `bash verify.sh`.

### 10. Testability

The core decision must be a pure function, testable without spawning processes:

```text
retryPlan(plan, parentSnapshot, adoptableDaemons) -> RetryPlan { rerun, reuse, reasons }
```

Daemon adoption is stage 3 and needs real processes to verify.

### 11. Acceptance criteria

- A 12-node diamond pipeline whose last node fails reruns only that node after a fix, marking the other 11 as reused.
- Changing only an unrelated task's `description` still reuses every successful node, proving the fingerprint scope is correct.
- Changing only one task's `cmd` reruns that node and all its descendants, reusing the rest.
- A ready daemon with matching identity is not restarted, and downstream tasks still run.
- A daemon that exited causes its node and descendants to rerun.
- A parent still executing returns `13` unless `--force` confirms no live executor.
- `retry --dry-run --format json` is directly consumable, with no log reading required.
