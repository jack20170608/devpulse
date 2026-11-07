# devpulse-core 使用说明 / devpulse-core usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD) | [架构设计](../docs/devpulse-core/architecture.md)

### 范围与入口

`devpulse-core` 的 `io.github.devpulse.core.dag` 提供单 JVM、本地、静态 DAG 框架，适用于少于 5000 个节点的工作流。仅依赖 JDK 25，不读取配置文件、不注册 CLI 或 JVM 退出钩子。现有 `core.execution` Bash 引擎与 Pulse 的配置、命令及行为不变；两套 API 暂不互相适配。

`DagWorkflow.builder()` 定义 Java 任务；`build()` 在执行前拒绝空工作流、重复节点或依赖、自依赖、未知依赖、结果类型不匹配及循环依赖。依赖可以前向声明。校验与拓扑排序采用非递归算法，复杂度为 O(V + E)，无固定节点数上限。Builder 不保证线程安全；构建后的定义可复用，每次调用 `newRun` 创建独立运行状态，但任务 Lambda 捕获的对象仍由调用方负责线程安全。

### 使用示例

以下示例中 A 的结果供 B/C 并行读取，D 等待 B/C 成功后汇总：

```java
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.dag.TaskOptions;
import java.time.Duration;
import java.util.List;

public class DagExample {
    public static void main(String[] args) throws InterruptedException {
        var a = TaskKey.of("A", Integer.class);
        var b = TaskKey.of("B", Integer.class);
        var c = TaskKey.of("C", Integer.class);
        var d = TaskKey.of("D", Integer.class);
        var options = new TaskOptions(3, Duration.ofMillis(100), Duration.ofSeconds(2));

        var workflow = DagWorkflow.builder()
                .task(a, context -> 10)
                .task(b, List.of(a), context -> context.result(a) * 2, options)
                .task(c, List.of(a), context -> context.result(a) + 1)
                .task(d, List.of(b, c), context -> context.result(b) + context.result(c))
                .build();

        var run = workflow.newRun(2, event -> {
            if (event.task() != null) {
                System.out.println(event.task().id() + ": " + event.result().state());
            }
        });
        var result = run.execute();
        System.out.println(result.state());
        System.out.println(result.result(d)); // 31
    }
}
```

从仓库根目录构建，并运行保存为 `DagExample.java` 的示例：

```powershell
mvn -q -pl devpulse-core -am -DskipTests package
java --class-path devpulse-core\target\classes DagExample.java
```

### 结果与状态

- `TaskKey<T>` 由节点 ID 与 `Class<T>` 组成；同一 ID 不可声明不同任务。使用包装类型，不使用原始类型的 `class`；无返回值任务可用 `Void.class` 并返回 `null`。
- `TaskContext.result(key)` 只允许读取已声明的直接依赖结果；未知节点、传递依赖或不匹配的类型均报错。节点返回值经过类型检查，允许 `null`；框架不深拷贝结果，多个下游共享同一对象，应优先返回不可变对象或 record。
- `context.taskId()`、`context.attempt()` 分别提供当前 ID 和从 1 开始的尝试次数。`context.checkCancelled()` 用于协作式中断检查。
- `run.snapshot()` 可从其他线程读取不可修改的状态快照；包含全部节点的状态、已开始的尝试次数、结果、异常和起止时间。快照反映协调线程已经处理的事件，不是任务线程的瞬时状态。
- `Result.result(key)` 仅在对应节点 `SUCCESS` 时返回值，否则抛异常并保留节点失败原因。可通过 `Result.task(key).error()` 查看错误；`Result.error()` 记录协调线程中断、监听器异常或致命错误，普通节点错误记录在节点上。

| 节点状态 | 含义 |
| --- | --- |
| `PENDING` | 等待依赖或并发名额 |
| `RUNNING` | 当前尝试正在执行 |
| `RETRYING` | 本次尝试失败，等待重试 |
| `SUCCESS` | 成功，结果可供下游读取 |
| `FAILED` | 普通异常重试耗尽，或非超时中断 |
| `TIMED_OUT` | 单次尝试超时，重试耗尽 |
| `SKIPPED` | 前置节点未成功，不执行任务 |
| `CANCELLED` | 工作流取消，不再执行或继续重试 |

整体状态为 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED` 或 `CANCELLED`。所有节点成功才算整体成功；最终失败节点的后继被跳过，独立分支继续。状态、结果和事件只保存在内存中。

### 调度、重试与超时

- `newRun(parallelism)` 要求正数并发上限，以虚拟线程运行任务。依赖全部成功后即可调度，不等待同层其他节点结束。调度就绪队列以声明顺序和依赖完成顺序为依据，不保证并行任务的完成顺序。
- `TaskOptions.DEFAULT` 为尝试一次、零重试间隔、无超时。`maxAttempts` 包含首次执行；异常和超时可重试，普通 `InterruptedException` 不重试。间隔为固定时长，允许零；超时必须为正时长，每次尝试重新计时，不包含重试等待。
- 一个节点从首次开始到最后结束，包括重试等待，持续占用一个并发名额。重试不会重叠执行；任务可能重复产生副作用，调用方应保证幂等性。
- 超时会中断该任务线程；任务即使在超过期限后正常返回，该尝试仍按超时处理。框架必须等上次调用实际退出后才能重试或释放名额，不能安全强杀 Java 线程。忽略中断的任务可能延迟甚至阻止运行结束；阻塞操作应响应中断，CPU 循环应调用 `checkCancelled()`。
- 非 `Exception` 的 `Error` 不重试，会取消未完成任务并向 `execute()` 调用方抛出原错误。

### 取消与事件

`execute()` 阻塞至任务退出，每个运行只能调用一次。可从其他线程调用 `cancel()`：第一次有效请求返回 `true`，重复请求或结束后调用返回 `false`。执行前取消不会启动任务，调用 `execute()` 后所有节点成为 `CANCELLED`。执行中取消会停止新调度、取消未启动节点并中断运行中的任务；已完成的节点保留结果。外部中断协调线程同样请求取消，但 `execute()` 抛出 `InterruptedException`，可用 `snapshot()` 查看状态。

监听器在调用 `execute()` 的协调线程上串行接收开始、节点状态变化及整体结束事件。整体事件的 `task()` 与 `result()` 为 `null`。监听器可以读取快照或请求取消，应快速返回，不应等待当前工作流结束。监听器异常不会被吞掉：框架取消未完成任务、记录整体 `FAILED` 状态，并向调用方抛出原异常；异常退出时不保证收到结束事件。

### TODO：高级功能

- [ ] 本地运行记录持久化：确定存储格式、输出序列化与保留策略。
- [ ] 进程重启后恢复：定义检查点、任务幂等性与副作用恢复语义。
- [ ] 条件分支 / 跳过条件：定义条件求值及汇聚节点触发规则。
- [ ] 动态增加节点：定义运行中图修改、增量校验与调度一致性。
- [ ] 子工作流：定义嵌套结果、取消传播与共享并发预算。
- [ ] Bash 任务适配：复用已有 Bash 组件，明确进程清理和超时语义。
- [ ] 配置文件适配与 CLI 接入：保持 Java 核心不依赖配置或命令层。

### 嵌入 Bash 工作流运行时

**独立计划模块**：`devpulse-core` 的不可变 `TaskDefinition`、`WorkflowDefinition` 和 `TaskPlan` 只依赖 JDK，不依赖 YAML、Typesafe Config 或 CLI。任务定义包含命令、依赖和可选的长驻进程选项；工作流定义包含任务列表与流水线目标。`TaskPlan.create` 校验完整定义，再为指定任务或流水线补齐传递依赖并生成拓扑有序的计划；不会读取配置、探测 Bash、执行命令或写入运行记录。

```java
import io.github.devpulse.core.execution.TaskDefinition;
import io.github.devpulse.core.execution.TaskPlan;
import io.github.devpulse.core.execution.WorkflowDefinition;
import java.util.List;
import java.util.Map;

var workflow = new WorkflowDefinition(
        List.of(
                new TaskDefinition("check", "echo check", List.of()),
                new TaskDefinition("build", "echo build", List.of("check"))),
        Map.of("dev", List.of("build")));
var plan = TaskPlan.create(workflow, "dev");
```

`PlanningException` 通过 `scope()`、`name()` 和 `field()` 提供与配置格式无关的错误定位；Pulse 的 `PulseConfiguration.plan` 将其关联到原 YAML 文件和可用行号。长驻进程的终端说明由 Pulse 的 `TaskDetails` 生成，不进入核心定义。

**独立执行运行时**：core 的 `WorkflowRun` 绑定一个计划和并发上限，阻塞式 `execute()` 只能调用一次，返回整体状态及成功、失败、跳过数量。`cancel()` 可从其他线程调用；执行前取消不会启动任务，重复取消和完成后取消不改变已结束的结果，执行中的外部取消会中断执行线程。调用方用 try-with-resources 管理 `RunHistory.Writer`，并自行决定是否注册 JVM 退出钩子；core 不注册全局钩子。Pulse 将成功结果映射为退出码 `0`，其他结果映射为 `1`，中断映射为 `130`。

`RunHistory` 和 `DaemonManager` 也位于 core，保留原有调度、失败策略与版本 `1` 的 HOCON 记录格式。执行运行时依赖 Typesafe Config 读写这些记录，但不读取任务 YAML、不依赖 picocli 或 TUI。嵌入调用示例（接续上面的 `plan`）：

```java
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.execution.RunHistory;
import io.github.devpulse.core.execution.WorkflowRun;
import java.io.PrintWriter;
import java.nio.file.Path;

var directory = Path.of(".").toAbsolutePath().normalize();
var shell = BashShell.discover(null);
try (var history = new RunHistory(directory).begin("dev", plan, 4)) {
    var run = new WorkflowRun(shell, directory, new PrintWriter(System.out),
            history, plan, 4);
    var result = run.execute();
}
```

以上片段按顺序在同一方法内使用。调用方负责历史 Writer 的关闭与退出钩子；不要将本地 Java DAG 的失败策略、重试或超时语义套用于 Bash 运行时。CLI 配置与命令见 [Pulse 使用说明](../devpulse-plugins/pulse/README.md)。

## English

[Project home](../readme.MD) | [Architecture](../docs/devpulse-core/architecture.md)

### Scope and entry points

`io.github.devpulse.core.dag` in `devpulse-core` provides a local, static DAG framework for a single JVM, intended for workflows with fewer than 5000 nodes. It depends only on JDK 25 and reads no configuration files or registers CLI commands or JVM shutdown hooks. The existing `core.execution` Bash engine and Pulse configuration, commands, and behavior remain unchanged; the two APIs are not yet adapted to each other.

`DagWorkflow.builder()` defines Java tasks; `build()` rejects empty workflows, duplicate nodes or dependencies, self-dependencies, unknown dependencies, result type mismatches, and cycles before execution. Forward references are supported. Validation and topological sorting are iterative with O(V + E) complexity and no hard node limit. Builders are not thread-safe; built definitions are reusable and each `newRun` creates independent execution state, but callers remain responsible for thread safety of objects captured by task Lambdas.

### Usage example

In this example B/C read A's result in parallel, and D aggregates after B/C succeed:

```java
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.dag.TaskOptions;
import java.time.Duration;
import java.util.List;

public class DagExample {
    public static void main(String[] args) throws InterruptedException {
        var a = TaskKey.of("A", Integer.class);
        var b = TaskKey.of("B", Integer.class);
        var c = TaskKey.of("C", Integer.class);
        var d = TaskKey.of("D", Integer.class);
        var options = new TaskOptions(3, Duration.ofMillis(100), Duration.ofSeconds(2));

        var workflow = DagWorkflow.builder()
                .task(a, context -> 10)
                .task(b, List.of(a), context -> context.result(a) * 2, options)
                .task(c, List.of(a), context -> context.result(a) + 1)
                .task(d, List.of(b, c), context -> context.result(b) + context.result(c))
                .build();

        var run = workflow.newRun(2, event -> {
            if (event.task() != null) {
                System.out.println(event.task().id() + ": " + event.result().state());
            }
        });
        var result = run.execute();
        System.out.println(result.state());
        System.out.println(result.result(d)); // 31
    }
}
```

Build from the repository root and run the example saved as `DagExample.java`:

```powershell
mvn -q -pl devpulse-core -am -DskipTests package
java --class-path devpulse-core\target\classes DagExample.java
```

### Results and states

- `TaskKey<T>` combines a node ID and `Class<T>`; different tasks cannot share an ID. Use boxed types rather than primitive class tokens; tasks without a return value can use `Void.class` and return `null`.
- `TaskContext.result(key)` only reads declared direct dependency results; unknown nodes, transitive dependencies, and mismatched types raise errors. Return values are type-checked and may be `null`; the framework does not deep-copy results, so downstream tasks share the same object. Prefer immutable objects or records.
- `context.taskId()` and `context.attempt()` expose the current ID and 1-based attempt number. `context.checkCancelled()` checks for cooperative interruption.
- `run.snapshot()` provides an unmodifiable state snapshot readable from other threads, including every node's state, started attempt count, result, exception, and start/end times. Snapshots reflect events already processed by the coordinator rather than instantaneous worker state.
- `Result.result(key)` returns a value only for `SUCCESS`; otherwise it throws with the node's failure cause. Inspect errors with `Result.task(key).error()`. `Result.error()` records coordinator interruption, listener exceptions, or fatal errors; ordinary task errors reside on individual nodes.

| Node state | Meaning |
| --- | --- |
| `PENDING` | Waiting for dependencies or a concurrency slot |
| `RUNNING` | Executing the current attempt |
| `RETRYING` | An attempt failed; waiting to retry |
| `SUCCESS` | Succeeded; downstream tasks may read the result |
| `FAILED` | Ordinary exceptions exhausted retries, or non-timeout interruption |
| `TIMED_OUT` | An attempt timed out and retries were exhausted |
| `SKIPPED` | A prerequisite did not succeed; the task is not executed |
| `CANCELLED` | Workflow cancelled; no execution or further retry |

Overall states are `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, and `CANCELLED`. Every node must succeed for overall success; descendants of a finally failed node are skipped while independent branches continue. State, results, and events are in-memory only.

### Scheduling, retries, and timeouts

- `newRun(parallelism)` requires a positive concurrency limit and runs tasks on virtual threads. Tasks become eligible as soon as all dependencies succeed, without waiting for other nodes in the same layer. The ready queue follows declaration order and dependency completion order; parallel completion order is not guaranteed.
- `TaskOptions.DEFAULT` means one attempt, zero retry delay, and no timeout. `maxAttempts` includes the initial attempt; exceptions and timeouts can be retried, but ordinary `InterruptedException` is not retried. Delays are fixed and may be zero; timeouts must be positive, restart for each attempt, and exclude retry waits.
- A node occupies one concurrency slot from its first attempt until its final outcome, including retry waits. Attempts never overlap; tasks may repeat side effects, so callers should ensure idempotency.
- A timeout interrupts the worker; even normal returns after the deadline count as timeouts. The previous invocation must actually exit before retrying or releasing its slot; Java threads cannot be safely force-killed. Tasks ignoring interruption may delay or prevent completion. Blocking operations should respond to interruption and CPU loops should call `checkCancelled()`.
- An `Error`, rather than an `Exception`, is not retried: unfinished tasks are cancelled and the original error is thrown to the `execute()` caller.

### Cancellation and events

`execute()` blocks until tasks exit and may be called only once per run. Another thread may call `cancel()`: the first effective request returns `true`, while repeated requests or calls after completion return `false`. Cancellation before execution starts no tasks; all nodes become `CANCELLED` when `execute()` is called. During execution, cancellation stops new scheduling, cancels unstarted nodes, and interrupts workers; completed nodes retain their outcomes. External coordinator interruption also requests cancellation, but `execute()` throws `InterruptedException`; inspect state with `snapshot()`.

The listener receives start, node-transition, and overall-finish events serially on the coordinator thread calling `execute()`. Overall events have `null` `task()` and `result()`. Listeners may inspect snapshots or request cancellation, should return promptly, and must not wait for the current workflow to finish. Listener exceptions are not swallowed: unfinished tasks are cancelled, the overall state becomes `FAILED`, and the original exception is thrown to the caller. An end event is not guaranteed on exceptional exit.

### TODO: Advanced features

- [ ] Local run-history persistence: define storage format, result serialization, and retention.
- [ ] Recovery after process restart: define checkpoints, task idempotency, and side-effect recovery semantics.
- [ ] Conditional branches / skip predicates: define condition evaluation and join trigger rules.
- [ ] Dynamic node insertion: define runtime graph mutation, incremental validation, and scheduling consistency.
- [ ] Subworkflows: define nested results, cancellation propagation, and shared concurrency budgets.
- [ ] Bash task adapter: reuse existing Bash components and define process cleanup and timeout semantics.
- [ ] Configuration adapters and CLI integration: keep the Java core independent of configuration and command layers.

### Embed the Bash workflow runtime

**Independent planning module**: The immutable `TaskDefinition`, `WorkflowDefinition`, and `TaskPlan` in `devpulse-core` depend only on the JDK rather than YAML, Typesafe Config, or the CLI. Task definitions contain commands, dependencies, and optional daemon settings; workflow definitions contain task lists and pipeline targets. `TaskPlan.create` validates the complete definition, expands transitive dependencies for a selected task or pipeline, and returns a topologically ordered plan without reading configuration, discovering Bash, executing commands, or writing run records.

```java
import io.github.devpulse.core.execution.TaskDefinition;
import io.github.devpulse.core.execution.TaskPlan;
import io.github.devpulse.core.execution.WorkflowDefinition;
import java.util.List;
import java.util.Map;

var workflow = new WorkflowDefinition(
        List.of(
                new TaskDefinition("check", "echo check", List.of()),
                new TaskDefinition("build", "echo build", List.of("check"))),
        Map.of("dev", List.of("build")));
var plan = TaskPlan.create(workflow, "dev");
```

`PlanningException` exposes format-independent error locations through `scope()`, `name()`, and `field()`. Pulse's `PulseConfiguration.plan` associates these with the original YAML file and available line numbers. Pulse's `TaskDetails` formats daemon terminal descriptions rather than placing them in core definitions.

**Independent execution runtime**: Core's `WorkflowRun` binds a plan and concurrency limit. Its blocking `execute()` may be called only once and returns the overall state and succeeded, failed, and skipped counts. `cancel()` may be called from another thread: cancellation before execution starts no tasks, repeated cancellation and cancellation after completion do not change terminal results, and external cancellation during execution interrupts the execution thread. Callers manage `RunHistory.Writer` with try-with-resources and decide whether to register a JVM shutdown hook; core registers no global hooks. Pulse maps success to exit code `0`, other results to `1`, and interruption to `130`.

`RunHistory` and `DaemonManager` also reside in core, preserving scheduling, failure policy, and version `1` HOCON record formats. The execution runtime uses Typesafe Config for these records but does not read task YAML or depend on picocli or the TUI. Embedded usage (continuing with the `plan` above):

```java
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.execution.RunHistory;
import io.github.devpulse.core.execution.WorkflowRun;
import java.io.PrintWriter;
import java.nio.file.Path;

var directory = Path.of(".").toAbsolutePath().normalize();
var shell = BashShell.discover(null);
try (var history = new RunHistory(directory).begin("dev", plan, 4)) {
    var run = new WorkflowRun(shell, directory, new PrintWriter(System.out),
            history, plan, 4);
    var result = run.execute();
}
```

Use the snippets in order inside the same method. Callers own the history Writer and shutdown hooks; do not apply the local Java DAG failure, retry, or timeout semantics to the Bash runtime. See the [Pulse guide](../devpulse-plugins/pulse/README.md) for CLI configuration and commands.
