# devpulse-core 使用说明 / devpulse-core usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD) | [架构设计](../docs/devpulse-core/architecture.md) | [Bash 执行后端](../devpulse-plugin/bash/README.md)

### 范围与入口

`devpulse-core` 只提供单 JVM、本地、静态的 DAG 框架，适用于少于 5000 个节点的工作流，包名为 `io.github.devpulse.core.dag`。它**仅依赖 JDK 21**：不依赖 Maven 仓库里的任何库，不读取配置文件，不注册 CLI 命令或 JVM 退出钩子，也不包含任何进程、Bash 或持久化代码。

Bash 执行、长驻进程管理与运行记录分别在 [`devpulse-plugin/bash`](../devpulse-plugin/bash/README.md) 和 [`devpulse-execution`](../devpulse-execution/README.md) 中；它们通过 `DagTask` / `DagDaemon` 挂到本模块的调度器上，本模块不认识脚本语言。

### 核心类型

| 类型 | 作用 |
|------|------|
| `DagGraph` | 唯一的不可变图定义：类型化身份、依赖、校验与目标依赖闭包 |
| `DagWorkflow` | 把执行实现绑定到图上，产出可重复执行的运行定义 |
| `DagTask<T>` / `DagDaemon<T>` | sealed 的普通任务与长驻任务 |
| `TaskKey<T>` / `TaskContext<T>` | 为直接依赖提供类型化结果读取 |
| `DagRun` | 就绪队列 + 未完成依赖计数的调度器 |
| `TaskOptions` / `DaemonOptions` | 重试、超时、健康检查等执行选项 |

### 使用示例

```java
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;

var a = TaskKey.of("a", Integer.class);
var b = TaskKey.of("b", String.class);

var workflow = DagWorkflow.builder()
        .task(a, () -> 1)
        .task(b, ctx -> "value=" + ctx.get(a))
        .needs(b, a)
        .build();

var run = workflow.newRun();
var result = run.execute();
```

`DagWorkflow.builder()` 定义任务；`build()` 在执行前拒绝空工作流、重复节点或依赖、自依赖、未知依赖、结果类型不匹配及循环依赖。依赖可以前向声明。校验与拓扑排序采用非递归算法，复杂度为 O(V + E)，无固定节点数上限。Builder 不保证线程安全；构建后的定义可复用，每次调用 `newRun` 创建独立运行状态，但任务 Lambda 捕获的对象仍由调用方负责线程安全。

### 结果与状态

每次运行结束返回整体状态，以及成功、失败、跳过的节点数量。普通任务失败只跳过它的后代，独立分支继续执行；长驻任务失去健康会取消其运行中的依赖者、跳过尚未启动的依赖者。失败不会把整个运行立刻终止，调用方根据汇总结果决定退出码。

### 调度、重试与超时

调度器为每个可运行节点启动一个虚拟线程，未完成依赖计数归零时把节点加入就绪队列。重试由 `TaskOptions` 声明，策略与次数由调用方决定；超时与取消使用协作式中断，被中断的任务应当尽快退出，忽略中断会阻止运行结束。

### 取消与事件

运行暴露取消入口与事件流；事件包括节点更新、完成与长驻任务健康变化，调用方据此渲染进度或记录历史。本模块不打印、不写文件，也不决定退出码。

## English

[Project home](../readme.MD) | [Architecture](../docs/devpulse-core/architecture.md) | [Bash execution backend](../devpulse-plugin/bash/README.md)

### Scope and entry points

`devpulse-core` provides only a local, static DAG framework for a single JVM, intended for workflows with fewer than 5000 nodes, under the package `io.github.devpulse.core.dag`. It depends on **JDK 21 alone**: no Maven dependencies, no configuration files, no CLI commands or JVM shutdown hooks, and no process, Bash, or persistence code.

Bash execution, daemon management, and run records live in [`devpulse-plugin/bash`](../devpulse-plugin/bash/README.md) and [`devpulse-execution`](../devpulse-execution/README.md); they attach to this module's scheduler through `DagTask` / `DagDaemon`, and this module never recognises a script language.

### Core types

| Type | Role |
|------|------|
| `DagGraph` | The single immutable graph definition: typed identity, dependencies, validation, target closure |
| `DagWorkflow` | Binds execution implementations to a graph and yields a reusable run definition |
| `DagTask<T>` / `DagDaemon<T>` | Sealed ordinary task and long-running task |
| `TaskKey<T>` / `TaskContext<T>` | Typed result access for direct dependencies |
| `DagRun` | Scheduler built on a ready queue plus outstanding-dependency counters |
| `TaskOptions` / `DaemonOptions` | Retry, timeout, and health-check execution options |

### Usage example

```java
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;

var a = TaskKey.of("a", Integer.class);
var b = TaskKey.of("b", String.class);

var workflow = DagWorkflow.builder()
        .task(a, () -> 1)
        .task(b, ctx -> "value=" + ctx.get(a))
        .needs(b, a)
        .build();

var run = workflow.newRun();
var result = run.execute();
```

`DagWorkflow.builder()` defines tasks; `build()` rejects empty workflows, duplicate nodes or dependencies, self-dependencies, unknown dependencies, result type mismatches, and cycles before execution. Forward references are supported. Validation and topological sorting are iterative with O(V + E) complexity and no hard node limit. Builders are not thread-safe; built definitions are reusable and each `newRun` creates independent execution state, but callers remain responsible for thread safety of objects captured by task Lambdas.

### Results and states

Every run returns an overall state plus the number of succeeded, failed, and skipped nodes. A failed task skips only its descendants while independent branches keep running; a daemon that loses health cancels its running dependents and skips the ones that never started. A failure never aborts the whole run immediately; the caller decides the exit code from the summary.

### Scheduling, retries, and timeouts

The scheduler starts one virtual thread per runnable node and enqueues a node when its outstanding-dependency counter reaches zero. Retries are declared through `TaskOptions`, with the strategy and count chosen by the caller. Timeouts and cancellation use cooperative interruption; interrupted tasks should exit promptly, and ignoring interruption prevents the run from finishing.

### Cancellation and events

A run exposes a cancellation entry point and an event stream covering node updates, completions, and daemon health changes, so callers can render progress or record history. This module prints nothing, writes no files, and decides no exit codes.
