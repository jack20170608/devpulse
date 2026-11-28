# devpulse-core 架构设计 / devpulse-core architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [使用说明](../../devpulse-core/README.md)

### 模块边界

`devpulse-core` 是**纯调度库**，包名 `io.github.devpulse.core.dag`，**只依赖 JDK 21**：没有 Maven 依赖、没有配置文件、没有进程管理、没有持久化，也不注册 CLI 命令或 JVM 退出钩子。

它不认识任何脚本语言，也不知道任务如何执行。Bash 执行、长驻进程与运行记录分别位于：

| 能力 | 模块 | 挂载方式 |
|------|------|----------|
| Bash 进程执行与 Daemon 管理 | [`devpulse-plugin/bash`](../../devpulse-plugin/bash/README.md) | 提供 `DagTask<Integer>` / `DagDaemon<ProcessHandle>` |
| 配置解析、计划装配、运行记录 | [`devpulse-execution`](../../devpulse-execution/README.md) | 消费 `DagRun` 事件并写入存储 |
| 运行状态持久化 | [`devpulse-storage`](../../devpulse-storage/README.md) | `RunStore` 及记录类型 |

### Java DAG 定义与调度

`DagGraph` 是唯一的不可变图定义，保存类型化身份与依赖，通过非递归 Kahn 算法校验依赖、类型与循环，并提供目标依赖闭包。`DagWorkflow` 绑定执行实现，通过 sealed Action 区分普通 `DagTask` 与 `DagDaemon`；可直接建图或复用已有 `DagGraph`，后者不重复规划。`DagWorkflow.select` 选取带依赖的子图并保留执行实现。

`DagRun` 以就绪队列与未完成依赖计数调度虚拟线程；工作线程经 `Update` / `Completed` 报告，Daemon 监测经 `HealthChanged` 报告，协调线程串行更新状态和发送事件。`TaskKey<T>` 与 `TaskContext` 限制直接依赖的类型化结果读取。

普通任务成功退出、Daemon 就绪均释放依赖。Handle 提供结果与健康检测，不提供自动停止；就绪后释放启动槽位并继续监测。节点就绪结果与健康观测分别保存；失效跳过未启动后继、取消运行中后继，并将依赖它的已就绪 Daemon 标记为不可用，独立分支继续。结束前停止并等待监测退出、做最终健康检查，不清理长驻线程或进程。重试、超时与取消使用协作式中断，忽略中断可能阻止结束。

### 为什么保持零依赖

调度器的每条规则都要能被上层以不同方式复用：Bash 后端、将来的其它执行后端、以及测试里的纯 Java 任务。任何对配置库、数据库驱动或进程 API 的依赖都会把“调度”和“执行”重新耦合回去，因此这里刻意不引入任何库——新增功能的默认位置是调用方模块，而不是 core。

## English

[Architecture index](../architecture.md) | [Usage](../../devpulse-core/README.md)

### Module boundaries

`devpulse-core` is a **pure scheduling library** in the `io.github.devpulse.core.dag` package, depending on **JDK 21 alone**: no Maven dependencies, no configuration files, no process management, no persistence, and no CLI commands or JVM shutdown hooks.

It recognises no script language and does not know how a task runs. Bash execution, daemons, and run records live elsewhere:

| Capability | Module | How it attaches |
|------------|--------|-----------------|
| Bash process execution and daemon management | [`devpulse-plugin/bash`](../../devpulse-plugin/bash/README.md) | Provides `DagTask<Integer>` / `DagDaemon<ProcessHandle>` |
| Configuration parsing, plan assembly, run records | [`devpulse-execution`](../../devpulse-execution/README.md) | Consumes `DagRun` events and writes storage |
| Run state persistence | [`devpulse-storage`](../../devpulse-storage/README.md) | `RunStore` and its records |

### Java DAG definitions and scheduling

`DagGraph` is the single immutable graph definition: typed identity, dependencies, non-recursive Kahn validation of dependencies, types and cycles, and target dependency closure. `DagWorkflow` binds execution implementations and uses sealed actions to distinguish `DagTask` from `DagDaemon`; it can build a graph or reuse an existing `DagGraph`, which is not planned twice. `DagWorkflow.select` keeps the execution implementations of a dependency-closed subgraph.

`DagRun` schedules virtual threads from a ready queue plus outstanding-dependency counters. Worker threads report through `Update` / `Completed`, daemon monitoring reports through `HealthChanged`, and the coordinator thread serializes state updates and event dispatch. `TaskKey<T>` and `TaskContext` restrict typed result access to direct dependencies.

A successful task or a ready daemon releases its dependents. Handles expose results and health checks but never stop anything automatically; readiness frees the startup slot while monitoring continues. Node results and health observations are stored separately: health loss skips unstarted successors, cancels running ones, and marks ready daemons that depend on it unavailable, while independent branches continue. Before finishing, the run stops and joins monitoring and performs a final health check, without cleaning up long-running threads or processes. Retries, timeouts, and cancellation use cooperative interruption, and ignoring interruption can prevent the run from finishing.

### Why zero dependencies

Every scheduling rule has to be reusable in different ways: a Bash backend, future execution backends, and plain Java tasks in tests. Any dependency on a config library, a database driver, or a process API would couple scheduling back to execution, so this module deliberately stays library-free; new behaviour belongs in the calling module, not in core.
