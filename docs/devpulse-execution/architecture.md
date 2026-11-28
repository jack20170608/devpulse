# devpulse-execution 架构 / devpulse-execution architecture

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

## 中文

### 位置

```
devpulse-core    ──┐
devpulse-plugin/bash    ──┼─→ devpulse-execution ──→ devpulse-cli
devpulse-storage ──┘                       └─→ devpulse-server
```

`devpulse-execution` 依赖 `devpulse-core`（调度）、`devpulse-plugin/bash`（执行器）与 `devpulse-storage`（持久化），被 CLI 与服务端复用。它不依赖任何 CLI 模块。运行记录相关类型（`RunHistory`、`PlanManifest`、`WorkflowRun`）在本模块，因为它们是"运行如何被记录与汇总"，不是 Bash 特有行为。

### 分工

| 层 | 负责 | 不负责 |
|----|------|--------|
| core | 纯 DAG 调度：图校验、就绪队列、协作取消 | YAML、进程、持久化 |
| bash | Bash 进程、Daemon、环境事实，`TaskHistory` 接缝 | YAML、退出码、渲染 |
| storage | 运行记录的持久化 | 业务语义、状态机 |
| **execution** | YAML → 计划 → 执行 → 记录与汇总 | 参数解析、输出渲染、退出码 |
| devpulse-cli | picocli 参数、呈现、退出码 | 执行细节 |

### 关键类型

- `PulseConfiguration` 只做"读取与校验"，不执行；`PulsePlan` 只做"解析与闭包"，不调度。
- `RunService.execute` 是唯一的执行入口：它绑定 `BashTaskAdapter`、创建 `WorkflowRun`、安装取消钩子、写入复用索引。
- `RunHistory` 是运行记录的唯一写入者，并实现 `devpulse-plugin/bash` 的 `TaskHistory` 接缝；这样执行器不必依赖本模块。
- `PlanManifest` 记录一次运行不可变的输入与指纹，`WorkflowRun` 只消费 `DagRun` 事件并汇总结果。
- 取消语义由 `RunService` 内的 shutdown hook 维护；调用方无需关心。

### 刻意不做的事

- 不渲染终端输出：`PrintWriter` 由调用方传入，可以指向空流。
- 不决定退出码：`Outcome` 返回结果，分类留给 CLI 与服务端各自定义。
- 不发现 Bash：`BashShell` 由调用方传入，这样"缺少 Bash"能保持独立的环境错误分类。

## English

### Position

```
devpulse-core    ──┐
devpulse-plugin/bash    ──┼─→ devpulse-execution ──→ devpulse-cli
devpulse-storage ──┘                       └─→ devpulse-server
```

`devpulse-execution` depends on `devpulse-core` (scheduling), `devpulse-plugin/bash` (executor), and `devpulse-storage` (persistence), and is reused by both the CLI and the server. It depends on no CLI module. The run-record types (`RunHistory`, `PlanManifest`, `WorkflowRun`) live here because they describe how a run is recorded and summarised, not how Bash behaves.

### Division of labour

| Layer | Owns | Does not own |
|-------|------|--------------|
| core | Pure DAG scheduling: graph validation, ready queue, cooperative cancellation | YAML, processes, persistence |
| bash | Bash processes, daemons, environment facts, the `TaskHistory` seam | YAML, exit codes, rendering |
| storage | Persistence of run records | Business semantics, state machines |
| **execution** | YAML → plan → execution → recording and summary | Argument parsing, rendering, exit codes |
| devpulse-cli | picocli arguments, presentation, exit codes | Execution details |

### Key types

- `PulseConfiguration` reads and validates only; `PulsePlan` resolves and computes closure only.
- `RunService.execute` is the single execution entry point: it binds `BashTaskAdapter`, creates `WorkflowRun`, installs the cancellation hook, and writes the reuse index.
- `RunHistory` is the only writer of run records and implements the `TaskHistory` seam from `devpulse-plugin/bash`, so the executor never depends on this module.
- `PlanManifest` records a run's immutable inputs and fingerprints, and `WorkflowRun` only consumes `DagRun` events and summarises the result.
- Cancellation semantics live inside `RunService`; callers do not manage the hook.

### Deliberate omissions

- No terminal rendering: the caller passes the `PrintWriter`, possibly over a null stream.
- No exit codes: `Outcome` returns the result; classification belongs to each front end.
- No Bash discovery: the caller passes `BashShell`, so a missing Bash keeps its own environment classification.
