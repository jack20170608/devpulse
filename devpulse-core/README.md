# devpulse-core 使用说明 / devpulse-core usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD) | [架构设计](../docs/devpulse-core/architecture.md)

### 范围与入口

`devpulse-core` 的 `io.github.devpulse.core.dag` 提供单 JVM、本地、静态 DAG 框架，适用于少于 5000 个节点的工作流。仅依赖 JDK 25，不读取配置文件、不注册 CLI 或 JVM 退出钩子。`io.github.devpulse.core.bash` 提供 Bash Adapter、进程管理、日志与 Bash 运行历史，使用同一个 DAG 调度器，不维护另一套执行调度。

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
| `SUCCESS` | 批任务成功退出，或 Daemon 已就绪，结果可供下游读取 |
| `FAILED` | 普通异常重试耗尽，或非超时中断 |
| `TIMED_OUT` | 单次尝试超时，重试耗尽 |
| `SKIPPED` | 前置节点未成功，不执行任务 |
| `CANCELLED` | 整体取消或 Daemon 失效导致受影响任务取消，不再继续重试 |

整体状态为 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED` 或 `CANCELLED`。所有节点成功且所有 Daemon 最后检测为就绪才算整体成功；最终失败节点的后继被跳过，独立分支继续。核心状态、结果和事件在内存中，Bash Adapter 另行保存运行历史。

### Daemon：就绪与健康监测

`builder.daemon(key, needs, daemon, daemonOptions)` 注册长驻任务。`DagDaemon.start(context)` 返回用户管理的 `DagDaemon.Handle<T>`；`value()` 提供下游使用的结果，`check()` 返回健康状态。Handle 可以代表 JVM 线程或外部进程，核心不判断线程或进程是否存活，也不调用停止方法。

| 健康状态 | 含义 |
| --- | --- |
| `STARTING` | 尚未就绪，启动阶段继续等待 |
| `READY` | 可用，满足依赖并释放启动并发名额 |
| `UNAVAILABLE` | 当前不可用，启动阶段可继续等待；就绪后视为故障 |
| `EXITED` | 已退出，启动失败或就绪后故障 |
| `UNKNOWN` | 无法确认；检查异常或超时也归入此状态 |

`DaemonOptions.DEFAULT` 为启动就绪期限 `30s`、检测间隔 `500ms`、单次检测时限 `2s`，三个时长均必须为正。启动期限包含 `start()` 与初次就绪检测；若额外指定 `TaskOptions.timeout`，采用两者较短的期限。启动阶段可按显式配置重试，但调用方必须处理重复启动及残留实例，核心不会停止前次实例；默认不重试。就绪后不自动重启。

节点的 `SUCCESS` 保存就绪阶段成功的事实，`Result.daemon(key)` / `Result.daemons()` 单独保存最后健康状态、错误和检测时间；Daemon 后续失效不会覆盖已完成节点的结果。失效会跳过尚未启动的全部传递后继，协作式取消正在运行的后继，并使依赖它的已就绪 Daemon 标记为不可用、停止监测；独立分支继续调度。不做回滚。

工作流结束前停止并等待监测线程退出，再对仍有效的 Daemon 做最终检测。成功、失败、取消或启动超时均不停止 Daemon，用户自行管理。检测与任务必须响应中断；忽略中断的检测也可能阻止运行结束。健康快照只代表检测时刻，不保证后续持续可用。JVM 线程不能脱离宿主 JVM 存活；不要用核心的任务工作线程或执行器承载长驻业务。

以下示例将用户管理的 JVM 线程作为 Daemon；实际初始化完成后才设置就绪标志，示例最后由用户代码停止线程：

```java
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import java.util.concurrent.atomic.AtomicBoolean;

public class DaemonExample {
    public static void main(String[] args) throws InterruptedException {
        var ready = new AtomicBoolean();
        var thread = Thread.ofPlatform().unstarted(() -> {
            ready.set(true);
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        var key = TaskKey.of("server", Thread.class);
        var workflow = DagWorkflow.builder().daemon(key, context -> {
            thread.start();
            return new DagDaemon.Handle<Thread>() {
                public Thread value() { return thread; }
                public DagDaemon.Health check() {
                    return !thread.isAlive() ? DagDaemon.Health.EXITED
                            : ready.get() ? DagDaemon.Health.READY : DagDaemon.Health.STARTING;
                }
            };
        }).build();
        try {
            var result = workflow.newRun(1).execute();
            System.out.println(result.daemon(key).health());
            System.out.println(thread.isAlive());
        } finally {
            thread.interrupt();
            thread.join();
        }
    }
}
```

### 调度、重试与超时

- `newRun(parallelism)` 要求正数并发上限，以虚拟线程运行任务。依赖全部成功后即可调度，不等待同层其他节点结束。调度就绪队列以声明顺序和依赖完成顺序为依据，不保证并行任务的完成顺序。
- `TaskOptions.DEFAULT` 为尝试一次、零重试间隔、无超时。`maxAttempts` 包含首次执行；异常和超时可重试，普通 `InterruptedException` 不重试。间隔为固定时长，允许零；超时必须为正时长，每次尝试重新计时，不包含重试等待。
- 一个节点从首次开始到最后结束，包括重试等待，持续占用一个并发名额。重试不会重叠执行；任务可能重复产生副作用，调用方应保证幂等性。
- 超时会中断该任务线程；任务即使在超过期限后正常返回，该尝试仍按超时处理。框架必须等上次调用实际退出后才能重试或释放名额，不能安全强杀 Java 线程。忽略中断的任务可能延迟甚至阻止运行结束；阻塞操作应响应中断，CPU 循环应调用 `checkCancelled()`。
- 非 `Exception` 的 `Error` 不重试，会取消未完成任务并向 `execute()` 调用方抛出原错误。

### 取消与事件

`execute()` 阻塞至任务退出，每个运行只能调用一次。可从其他线程调用 `cancel()`：第一次有效请求返回 `true`，重复请求或结束后调用返回 `false`。执行前取消不会启动任务，调用 `execute()` 后所有节点成为 `CANCELLED`。执行中取消会停止新调度、取消未启动节点并中断运行中的任务；已完成的节点保留结果。外部中断协调线程同样请求取消，但 `execute()` 抛出 `InterruptedException`，可用 `snapshot()` 查看状态。

监听器在调用 `execute()` 的协调线程上串行接收开始、节点状态变化及整体结束事件。整体事件的 `task()` 与 `result()` 为 `null`。监听器可以读取快照或请求取消，应快速返回，不应等待当前工作流结束。监听器异常不会被吞掉：框架取消未完成任务、记录整体 `FAILED` 状态，并向调用方抛出原异常；异常退出时不保证收到结束事件。

Daemon 健康变化事件通过 `Event.daemon()` 提供独立观测，同时附带节点结果；健康不变时更新内存检测时间，不重复发送事件。`run.fail(cause)` 用于持久化、日志等运行级基础设施故障，立即阻止新调度并请求取消未完成任务，最终向 `execute()` 调用方抛出异常；不要将普通业务失败升级为运行级故障。Adapter 必须在任务真正启动前完成必要的持久化，不能依赖异步处理的 `RUNNING` 事件保证顺序。

### TODO：高级功能

- [x] 统一普通任务与 Daemon 的依赖调度、并发、就绪监测和受影响分支取消。
- [x] Bash 任务适配、Pulse 配置与 CLI 接入，以及 Bash 本地日志和运行历史。
- [ ] 通用 DAG 运行记录持久化：结果与健康序列化、事件检查点、保留策略；不能将 Bash 历史等同于通用恢复。
- [ ] 进程重启后恢复：定义检查点、任务幂等性与副作用恢复语义。
- [ ] 条件分支 / 跳过条件：定义条件求值及汇聚节点触发规则。
- [ ] 动态增加节点：定义运行中图修改、增量校验与调度一致性。
- [ ] 子工作流：定义嵌套结果、取消传播与共享并发预算。
- [ ] Bash 批任务重试与超时配置：区分业务与基础设施故障，确认进程清理后才重试。
- [ ] Daemon 显式重启与实例更替：下游旧句柄、重连、重试预算和副作用规则；不默认自动重启或清理。
- [ ] 资源预算与其他 Adapter：独立限制长驻资源和检测并发，接入其他配置或执行方式，保持核心仅依赖 JDK。

### 嵌入 Bash 工作流运行时

**唯一图规划模块**：`DagGraph` 保存不可变的类型化节点身份与依赖，统一负责依赖校验、环检测、拓扑排序和目标依赖闭包。`DagWorkflow` 将任务实现绑定到图，仍是唯一的可执行工作流。直接使用 `DagWorkflow.builder()` 时自动构建图；需要先审阅、创建运行记录再绑定执行环境时，使用 `DagGraph.builder()` 和 `DagWorkflow.builder(graph)`，绑定过程不重复规划。`DagWorkflow.select` 支持按类型化目标选取工作流及其全部依赖。

**执行方式与生命周期分离**：`BashTaskDefinition` 明确表示 Bash 脚本，不是通用语言任务定义；`daemon` 为 `null` 时是普通任务，否则使用就绪与健康监测。JVM 内任务继续通过 `DagTask<T>` / `DagDaemon<T>` 注册，不将函数塞进脚本描述。当前 YAML 的 `type: task|daemon` 只表示生命周期，`cmd` 均由 Bash 解释，包括 `python check.py` 或 `java -jar app.jar`。尚不提供 `runner` 或语言枚举；将来需要直接启动外部程序时再引入参数列表形式的 Process Adapter，并用独立 `runner` 表达执行方式。

```java
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.bash.BashTaskAdapter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

var directory = Path.of(".").toAbsolutePath().normalize();
var shell = BashShell.discover(null);
var out = new PrintWriter(System.out);
var adapter = new BashTaskAdapter(shell, directory, out);
var number = TaskKey.of("number", Integer.class);
var build = TaskKey.of("build", Integer.class);
var summary = TaskKey.of("summary", String.class);
var workflow = DagWorkflow.builder()
        .task(number, context -> 42)
        .task(build, List.of(number), adapter.task("echo build"))
        .task(summary, List.of(number, build),
                context -> "Answer: " + context.result(number)
                        + ", exit code: " + context.result(build))
        .build();
var result = workflow.select(List.of(summary)).newRun(4).execute();
```

`BashTaskAdapter.task(command)` 返回 `DagTask<Integer>`，`daemon(command, configuration)` 返回 `DagDaemon<ProcessHandle>`；节点名称、依赖、重试、超时和 Daemon 监测选项由 DAG 管理。Adapter 不要求运行历史：批任务仅输出到给定控制台，Daemon 的日志与手动停止元数据保存在 `.devpulse\daemons\<id>`，由调用方管理资源，工作流结束不删除这些文件或停止进程。

`BashTaskDefinition.Daemon(readyCommand, stopCommand, stopTimeout)` 只保存 Bash 就绪命令与手动停止配置，不保存监测时限。监测策略只通过 `DagWorkflow.Builder.daemon` 的 `DaemonOptions` 指定；省略时使用 DAG 默认值。启动时的 `TaskContext.daemonOptions()` 提供实际策略，普通任务调用该方法会报错。实例元数据记录这份策略，手动查询不另设默认时限。

每次 Daemon 启动有独立实例 ID 与尝试编号，即使就绪超时后重试，也保留旧实例身份及其脚本，不覆盖或自动停止。`DaemonManager.instances(runDirectory, taskId)` 查询全部实例；`stop(runDirectory, taskId)` 手动停止全部实例，三参数 `stop(runDirectory, taskId, instanceId)` 只停止指定实例。录制型与非录制型 Adapter 都按任务归组实例，共享该任务的追加日志。

Pulse 的 `PulsePlan` 只装配配置并提供选定图的任务展示信息，不维护图算法；`PlanningException` 的配置定位与 `TaskDetails` 的终端说明也属于 Pulse。原 core 中的 `TaskPlan` 和 `WorkflowDefinition` 已移除，`BashTaskDefinition` 保留为 Bash 配置 DTO，不是第二套可执行图模型。

**运行记录与展示**：`WorkflowRun` 接收已装配的 `DagWorkflow`，仅消费 `DagRun` 事件、保存历史并返回汇总结果，不转换 Bash 定义、管理进程或输出 Pulse 终端文案。Daemon 通过工作流的显式生命周期识别，而不是根据结果类型推断；Bash Adapter 明确记录进程退出码，Java 任务返回整数不会被当作退出码。阻塞式 `execute()` 只能调用一次。`cancel()` 直接请求 DAG 取消；Adapter 响应中断并尽力回收批任务及可见后代，不停止 Daemon。调用方管理历史 Writer 与 JVM 退出钩子；Pulse 展示汇总并将成功映射为 `0`，其他结果为 `1`，协调线程中断为 `130`。任务实现可通过 `TaskContext.failRun` 报告全局基础设施故障；普通业务失败仍只影响后继。

`RunHistory` 使用版本 `1` HOCON，分别记录 Daemon 就绪节点结果与健康变化。Bash 配置暂不提供批任务重试或超时，但直接嵌入的 Bash 任务可使用 DAG 的 `TaskOptions`，显式重试追加任务日志。Daemon 就绪和检测使用 DAG 时限。历史模块依赖 Typesafe Config，不读取任务 YAML、不依赖 picocli 或 TUI，也不提供任意 Java 结果的通用序列化或恢复。带 Bash 历史的嵌入示例：

批任务日志读取响应中断，Bash 退出后读取剩余可用输出，不等待后代持有的管道 EOF；保留 UTF-8、CR/LF 分行和无末尾换行的输出。Windows/Git Bash 的部分后代可能不在 Java 可见进程树中，取消仅尽力回收可见进程，不能保证所有后代已退出。

```java
import io.github.devpulse.core.dag.DagGraph;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.bash.WorkflowRun;

var check = TaskKey.of("check", Integer.class);
var graph = DagGraph.builder()
        .node(check, List.of())
        .node(build, List.of(check))
        .build();
try (var history = new RunHistory(directory).begin("dev", graph, 4)) {
    var recorded = new BashTaskAdapter(shell, directory, out, history);
    var recordedWorkflow = DagWorkflow.builder(graph)
            .task(check, recorded.task("echo check"))
            .task(build, recorded.task("echo build"))
            .build();
    var run = new WorkflowRun(recordedWorkflow, history, 4);
    var recordedResult = run.execute();
}
```

以上片段按顺序在同一方法内使用。调用方负责历史 Writer 的关闭与退出钩子，以及 Daemon 的手动停止；CLI 配置与命令见 [Pulse 使用说明](../devpulse-plugins/pulse/README.md)。

## English

[Project home](../readme.MD) | [Architecture](../docs/devpulse-core/architecture.md)

### Scope and entry points

`io.github.devpulse.core.dag` in `devpulse-core` provides a local, static DAG framework for a single JVM, intended for workflows with fewer than 5000 nodes. It depends only on JDK 25 and reads no configuration files or registers CLI commands or JVM shutdown hooks. `io.github.devpulse.core.bash` supplies the Bash Adapter, process management, logs, and Bash run history, sharing the same DAG scheduler rather than maintaining a second execution scheduler.

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
| `SUCCESS` | Batch succeeded or daemon became ready; downstream tasks may read the result |
| `FAILED` | Ordinary exceptions exhausted retries, or non-timeout interruption |
| `TIMED_OUT` | An attempt timed out and retries were exhausted |
| `SKIPPED` | A prerequisite did not succeed; the task is not executed |
| `CANCELLED` | Overall cancellation or affected-task cancellation after daemon failure; no further retry |

Overall states are `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, and `CANCELLED`. Every node must succeed and every daemon must last be observed ready for overall success; descendants of a finally failed node are skipped while independent branches continue. Core state, results, and events are in memory; the Bash Adapter persists separate run history.

### Daemons: readiness and health monitoring

`builder.daemon(key, needs, daemon, daemonOptions)` registers a long-running task. `DagDaemon.start(context)` returns a user-managed `DagDaemon.Handle<T>`; `value()` supplies the downstream result and `check()` reports health. A Handle may represent a JVM thread or an external process; the core does not determine thread/process liveness or invoke stopping.

| Health | Meaning |
| --- | --- |
| `STARTING` | Not ready yet; keep waiting during startup |
| `READY` | Available; satisfies dependencies and releases the startup concurrency slot |
| `UNAVAILABLE` | Currently unavailable; may wait during startup, but a fault after readiness |
| `EXITED` | Exited; startup failure or a fault after readiness |
| `UNKNOWN` | Unconfirmed; also used for check exceptions and timeouts |

`DaemonOptions.DEFAULT` sets a `30s` startup/readiness deadline, `500ms` check interval, and `2s` individual check limit; all three must be positive. The startup deadline includes `start()` and initial readiness checking. If `TaskOptions.timeout` is also specified, the shorter deadline applies. Startup retries require explicit configuration, and callers must handle repeated launches and leftover instances because the core never stops previous instances; the default is no retry. Ready daemons are not automatically restarted.

Node `SUCCESS` preserves successful readiness; `Result.daemon(key)` / `Result.daemons()` separately retain the last health, error, and observation time. Later health loss does not overwrite completed node outcomes. It skips all unstarted transitive descendants, cooperatively cancels running descendants, and marks dependent ready daemons unavailable while ending their monitoring; independent branches keep scheduling. There is no rollback.

Before completion, the workflow stops and joins monitor threads, then performs final checks on still-valid daemons. Success, failure, cancellation, and startup timeouts never stop daemons; users manage them. Checks and tasks must respond to interruption; uncooperative checks can also prevent completion. Health snapshots describe observation times, not guaranteed future availability. JVM threads cannot outlive their host JVM; do not run long-lived business work on core task workers or executors.

This example represents a user-managed JVM thread as a daemon. Set readiness only after actual initialization; user code stops the thread at the end of the example:

```java
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import java.util.concurrent.atomic.AtomicBoolean;

public class DaemonExample {
    public static void main(String[] args) throws InterruptedException {
        var ready = new AtomicBoolean();
        var thread = Thread.ofPlatform().unstarted(() -> {
            ready.set(true);
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        var key = TaskKey.of("server", Thread.class);
        var workflow = DagWorkflow.builder().daemon(key, context -> {
            thread.start();
            return new DagDaemon.Handle<Thread>() {
                public Thread value() { return thread; }
                public DagDaemon.Health check() {
                    return !thread.isAlive() ? DagDaemon.Health.EXITED
                            : ready.get() ? DagDaemon.Health.READY : DagDaemon.Health.STARTING;
                }
            };
        }).build();
        try {
            var result = workflow.newRun(1).execute();
            System.out.println(result.daemon(key).health());
            System.out.println(thread.isAlive());
        } finally {
            thread.interrupt();
            thread.join();
        }
    }
}
```

### Scheduling, retries, and timeouts

- `newRun(parallelism)` requires a positive concurrency limit and runs tasks on virtual threads. Tasks become eligible as soon as all dependencies succeed, without waiting for other nodes in the same layer. The ready queue follows declaration order and dependency completion order; parallel completion order is not guaranteed.
- `TaskOptions.DEFAULT` means one attempt, zero retry delay, and no timeout. `maxAttempts` includes the initial attempt; exceptions and timeouts can be retried, but ordinary `InterruptedException` is not retried. Delays are fixed and may be zero; timeouts must be positive, restart for each attempt, and exclude retry waits.
- A node occupies one concurrency slot from its first attempt until its final outcome, including retry waits. Attempts never overlap; tasks may repeat side effects, so callers should ensure idempotency.
- A timeout interrupts the worker; even normal returns after the deadline count as timeouts. The previous invocation must actually exit before retrying or releasing its slot; Java threads cannot be safely force-killed. Tasks ignoring interruption may delay or prevent completion. Blocking operations should respond to interruption and CPU loops should call `checkCancelled()`.
- An `Error`, rather than an `Exception`, is not retried: unfinished tasks are cancelled and the original error is thrown to the `execute()` caller.

### Cancellation and events

`execute()` blocks until tasks exit and may be called only once per run. Another thread may call `cancel()`: the first effective request returns `true`, while repeated requests or calls after completion return `false`. Cancellation before execution starts no tasks; all nodes become `CANCELLED` when `execute()` is called. During execution, cancellation stops new scheduling, cancels unstarted nodes, and interrupts workers; completed nodes retain their outcomes. External coordinator interruption also requests cancellation, but `execute()` throws `InterruptedException`; inspect state with `snapshot()`.

The listener receives start, node-transition, and overall-finish events serially on the coordinator thread calling `execute()`. Overall events have `null` `task()` and `result()`. Listeners may inspect snapshots or request cancellation, should return promptly, and must not wait for the current workflow to finish. Listener exceptions are not swallowed: unfinished tasks are cancelled, the overall state becomes `FAILED`, and the original exception is thrown to the caller. An end event is not guaranteed on exceptional exit.

Daemon health-transition events expose observations through `Event.daemon()` alongside the node result. Unchanged health updates the in-memory observation time without repeating events. `run.fail(cause)` reports run-level infrastructure failures such as persistence or logging: it immediately blocks new scheduling and requests cancellation of unfinished tasks, then throws to the `execute()` caller. Do not escalate ordinary business failures to run-level faults. Adapters must persist required state before actually starting tasks rather than relying on asynchronously processed `RUNNING` events for ordering.

### TODO: Advanced features

- [x] Unified batch/daemon dependency scheduling, concurrency, readiness monitoring, and affected-branch cancellation.
- [x] Bash task adaptation, Pulse configuration/CLI integration, and Bash local logs and run history.
- [ ] General DAG persistence: result/health serialization, event checkpoints, and retention; Bash history is not general recovery.
- [ ] Recovery after process restart: define checkpoints, task idempotency, and side-effect recovery semantics.
- [ ] Conditional branches / skip predicates: define condition evaluation and join trigger rules.
- [ ] Dynamic node insertion: define runtime graph mutation, incremental validation, and scheduling consistency.
- [ ] Subworkflows: define nested results, cancellation propagation, and shared concurrency budgets.
- [ ] Bash batch retry/timeout configuration: distinguish business and infrastructure failures and confirm process cleanup before retrying.
- [ ] Explicit daemon restart and instance replacement: stale downstream handles, reconnection, retry budgets, and side effects; no default restart or cleanup.
- [ ] Resource budgets and other Adapters: independently limit long-lived resources and check concurrency, add other configuration/execution formats, and keep the core JDK-only.

### Embed the Bash workflow runtime

**Single graph planning module**: `DagGraph` stores immutable typed node identities and dependencies, owning dependency validation, cycle detection, topological ordering, and target dependency closure. `DagWorkflow` binds execution actions to the graph and remains the only executable workflow. `DagWorkflow.builder()` builds the graph automatically. To review a graph and create history before binding an execution environment, use `DagGraph.builder()` and `DagWorkflow.builder(graph)` without repeating planning. `DagWorkflow.select` selects typed targets with all their dependencies.

**Execution mode and lifecycle are separate**: `BashTaskDefinition` describes a Bash script, not a general language-specific task. A `null` `daemon` denotes an ordinary task; otherwise readiness/health monitoring applies. In-JVM tasks still register through `DagTask<T>` / `DagDaemon<T>` rather than storing functions in script definitions. Current YAML `type: task|daemon` describes lifecycle only; Bash interprets every `cmd`, including `python check.py` and `java -jar app.jar`. No `runner` or language enum is currently exposed. Introduce an argument-list Process Adapter only when direct external execution is needed, with a separate `runner` for execution mode.

```java
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.bash.BashTaskAdapter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

var directory = Path.of(".").toAbsolutePath().normalize();
var shell = BashShell.discover(null);
var out = new PrintWriter(System.out);
var adapter = new BashTaskAdapter(shell, directory, out);
var number = TaskKey.of("number", Integer.class);
var build = TaskKey.of("build", Integer.class);
var summary = TaskKey.of("summary", String.class);
var workflow = DagWorkflow.builder()
        .task(number, context -> 42)
        .task(build, List.of(number), adapter.task("echo build"))
        .task(summary, List.of(number, build),
                context -> "Answer: " + context.result(number)
                        + ", exit code: " + context.result(build))
        .build();
var result = workflow.select(List.of(summary)).newRun(4).execute();
```

`BashTaskAdapter.task(command)` returns `DagTask<Integer>`; `daemon(command, configuration)` returns `DagDaemon<ProcessHandle>`. DAG owns node names, dependencies, retries, timeouts, and daemon monitoring options. History is optional: batches only print to the supplied console, while daemon logs and manual-stop metadata live under `.devpulse\daemons\<id>`. Callers manage resources; workflow completion neither removes these files nor stops processes.

`BashTaskDefinition.Daemon(readyCommand, stopCommand, stopTimeout)` stores only the Bash readiness command and manual-stop configuration, not monitoring limits. Set monitoring policy only through `DaemonOptions` on `DagWorkflow.Builder.daemon`; omission uses DAG defaults. During startup, `TaskContext.daemonOptions()` exposes the actual policy and throws for ordinary tasks. Instance metadata records that policy, so manual queries do not invent different default limits.

Every daemon startup has an independent instance ID and attempt number. Retries after readiness timeouts retain earlier identities and scripts without overwriting or automatically stopping them. `DaemonManager.instances(runDirectory, taskId)` queries every instance; `stop(runDirectory, taskId)` manually stops all instances, while `stop(runDirectory, taskId, instanceId)` targets one. Recording and non-recording Adapters both group instances by task and share that task's append-only log.

Pulse's `PulsePlan` only assembles configuration and exposes selected-task presentation metadata, with no graph algorithms. Configuration locations in `PlanningException` and terminal formatting in `TaskDetails` also belong to Pulse. Former core `TaskPlan` and `WorkflowDefinition` are removed. `BashTaskDefinition` remains a Bash configuration DTO, not a second executable graph model.

**History and presentation**: `WorkflowRun` accepts an assembled `DagWorkflow`, consuming `DagRun` events, persisting history, and returning summary results without converting Bash definitions, managing processes, or printing Pulse-specific text. It recognizes daemons through explicit lifecycle, not result types. The Bash Adapter explicitly records process exit codes; integer Java results are not exit codes. Blocking `execute()` is single-use. `cancel()` directly requests DAG cancellation; the Adapter responds to interruption and attempts batch/visible-descendant cleanup without stopping daemons. Callers own history Writers and JVM shutdown hooks. Pulse prints summaries and maps success to `0`, other results to `1`, and coordinator interruption to `130`. Task implementations may report global infrastructure faults through `TaskContext.failRun`; ordinary business failures still affect descendants only.

`RunHistory` uses version `1` HOCON, separating daemon readiness outcomes from health transitions. Bash configuration does not yet expose batch retries/timeouts, but embedded Bash tasks can use DAG `TaskOptions`, with explicit retries appending task logs. Daemon startup and checks use DAG limits. History uses Typesafe Config without reading task YAML or depending on picocli or the TUI; it does not provide general Java result serialization or recovery. Embedded Bash history example:

Batch log readers respond to interruption and drain available output after Bash exits without waiting for descendants to close the pipe. UTF-8, CR/LF line splitting, and trailing output without a newline are preserved. Some Windows/Git Bash descendants may be absent from Java's process tree; cancellation attempts visible-process cleanup but cannot guarantee that every descendant exits.

```java
import io.github.devpulse.core.dag.DagGraph;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.bash.WorkflowRun;

var check = TaskKey.of("check", Integer.class);
var graph = DagGraph.builder()
        .node(check, List.of())
        .node(build, List.of(check))
        .build();
try (var history = new RunHistory(directory).begin("dev", graph, 4)) {
    var recorded = new BashTaskAdapter(shell, directory, out, history);
    var recordedWorkflow = DagWorkflow.builder(graph)
            .task(check, recorded.task("echo check"))
            .task(build, recorded.task("echo build"))
            .build();
    var run = new WorkflowRun(recordedWorkflow, history, 4);
    var recordedResult = run.execute();
}
```

Use the snippets in order inside the same method. Callers own the history Writer, shutdown hooks, and manual daemon stopping. See the [Pulse guide](../devpulse-plugins/pulse/README.md) for CLI configuration and commands.
