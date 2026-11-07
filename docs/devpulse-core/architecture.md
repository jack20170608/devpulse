# devpulse-core 架构设计 / devpulse-core architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [使用说明](../../devpulse-core/README.md)

### 模块边界

`devpulse-core` 是共享库，不注册 CLI、不加载插件、不读取 Pulse YAML，也不注册全局退出钩子。它包含彼此独立的 Java DAG API 与 Bash 工作流运行时；前者仅依赖 JDK，后者用 Typesafe Config 维护 HOCON 运行记录。

### Java DAG 定义与调度

`DagWorkflow` 保存不可变节点定义与拓扑顺序，Builder 校验任务标识与依赖，构建时通过非递归 Kahn 算法检查未知依赖、类型匹配与循环。`DagRun` 为每次运行分配独立状态，以就绪队列与未完成依赖计数调度虚拟线程；工作线程经 `Update` / `Completed` 消息向协调线程报告，协调线程串行更新状态并发送事件。`TaskKey<T>` 与 `TaskContext` 限制任务只读取类型匹配的直接依赖结果。

重试、单次尝试超时和取消都遵循协作式中断，不强杀线程。最终失败仅跳过后继，独立分支继续；状态和结果只保存在内存。详细语义、示例与高级功能 TODO 统一维护在模块使用说明中，不与 Bash 运行时的失败策略混用。

### Bash 基础组件（`BashShell.java`）

- **统一解释器**：所有 OS 均使用 Bash，Windows 使用 Git Bash；找不到可用 Bash 时明确报错，不回退到 `sh`、CMD、PowerShell 或 WSL。
- **定位顺序**：显式 `--bash` 路径优先，其次探测 PATH；Windows 额外检查 Git 安装位置，自动探测只接受具有 Git 安装标记的 Bash，避免误选 WSL 入口。
- **可用性验证**：通过短时子进程验证 Bash 能实际启动，而不是仅检查文件存在。
- **启动方式**：使用 `ProcessBuilder` 启动 `bash --noprofile --norc -s --`，普通任务通过标准输入写入完整 UTF-8 脚本后关闭输入；长驻进程、就绪和停止命令通过保存的脚本文件重定向标准输入。不自行按空格拆分，也不将命令文本放入 `-c` 参数，避免 Windows 命令行转义改变引号和变量表达式；不提供交互输入。
- **环境与目录**：使用 `ProcessBuilder.directory()` 设置工作目录，继承父进程环境，但移除 `BASH_ENV`、`ENV` 和 `BASH_FUNC_*`，避免自动执行环境指定的启动文件或导入用户函数。不依赖 alias 或用户启动脚本。
- **探测资源控制**：Bash 验证与环境扫描均设置超时，持续读取扫描输出，并清理探测进程。
- **平台边界**：统一 Bash 语法不代表工具、路径、文件权限和操作系统能力完全一致；Git Bash 不是完整 Linux 环境。

### 环境事实采集（`EnvironmentGatherer.java`）

`EnvironmentGatherer` 使用 `BashShell` 查找固定工具清单，检查项目根目录标志，并返回 `Facts` / `Tool` record；记录目录、OS、架构、Java 版本及 Bash 路径，不读取项目内容、不递归扫描、不执行工具版本或健康检查。事实列表通过防御性复制固定，提示词模板、编码警告和剪贴板属于 Gather 命令层，不进入 core。

### Bash 定义、计划与运行时

**独立计划模块**：`devpulse-core` 的不可变 `TaskDefinition`、`WorkflowDefinition` 和 `TaskPlan` 只依赖 JDK，不依赖 YAML、Typesafe Config 或 CLI。任务定义包含命令、依赖和可选的长驻进程选项；工作流定义包含任务列表与流水线目标。`TaskPlan.create` 校验完整定义，再为指定任务或流水线补齐传递依赖并生成拓扑有序的计划；不会读取配置、探测 Bash、执行命令或写入运行记录。

`PlanningException` 通过 `scope()`、`name()` 和 `field()` 提供与配置格式无关的错误定位；Pulse 的 `PulseConfiguration.plan` 将其关联到原 YAML 文件和可用行号。长驻进程的终端说明由 Pulse 的 `TaskDetails` 生成，不进入核心定义。

**独立执行运行时**：core 的 `WorkflowRun` 绑定一个计划和并发上限，阻塞式 `execute()` 只能调用一次，返回整体状态及成功、失败、跳过数量。`cancel()` 可从其他线程调用；执行前取消不会启动任务，重复取消和完成后取消不改变已结束的结果，执行中的外部取消会中断执行线程。调用方用 try-with-resources 管理 `RunHistory.Writer`，并自行决定是否注册 JVM 退出钩子；core 不注册全局钩子。Pulse 将成功结果映射为退出码 `0`，其他结果映射为 `1`，中断映射为 `130`。

`RunHistory` 和 `DaemonManager` 也位于 core，保留原有调度、失败策略与版本 `1` 的 HOCON 记录格式。执行运行时依赖 Typesafe Config 读写这些记录，但不读取任务 YAML、不依赖 picocli 或 TUI。嵌入调用示例见 [模块使用说明](../../devpulse-core/README.md)。

### 本地任务执行状态持久化（`RunHistory.java`）

**方案**：采用项目内文件持久化，不引入数据库。`pulse` 在启动任务前保存计划，持续保存任务状态与日志，支持多个独立运行同时执行。

```text
.devpulse\
  runs\
    <run-id>\
      state.conf
      run.lock
      logs\
        <task-id>.log
      daemons\
        <task-id>.conf
        <task-id>-start.sh
        <task-id>-ready.sh
        <task-id>-stop.sh
        <task-id>.lock
```

- **存储布局**：每次实际执行生成唯一 `run-id`，在项目配置所在目录的 `.devpulse\runs` 下创建独立运行目录，避免多次或并发运行相互覆盖。使用生成的 `task-id` 作为日志文件名，不直接将任务名作为路径。`--dry-run` 不创建运行记录。
- **状态格式**：`state.conf` 使用 UTF-8 HOCON，复用 Typesafe Config 读写。记录格式版本、运行 ID、目标、项目目录、实际并发上限、运行状态与起止时间，以及各任务的 ID、名称、依赖、状态、起止时间、退出码、失败原因和相对日志路径。尚未产生的结束时间、退出码等字段不写入。
- **状态语义**：任务状态区分 `PENDING`、`RUNNING`、`READY`、`SUCCESS`、`FAILED`、`SKIPPED`、`CANCELLED`、`INTERRUPTED`；`READY` 记录长驻进程在该次执行中通过就绪检查，并非最终退出结果。正常失败为 `FAILED`，因失败策略未启动的任务为 `SKIPPED`，主动取消导致终止的批任务为 `CANCELLED`，执行器异常退出后无法确认结果的任务为 `INTERRUPTED`。整体运行状态表示流水线结果，与长驻进程后续的实时状态分开。
- **写入时机与一致性**：执行前保存初始计划，启动任务前持久化 `RUNNING`，任务结束后保存结果，运行结束时写入整体结果；主动取消时尽力保存取消状态。同次运行的状态写入串行化，先写同目录临时文件，再通过原子替换更新 `state.conf`。文件系统不支持原子替换时明确报错，不退化为直接覆盖。初始化失败时不启动任务；执行中的持久化失败应明确报错、停止启动新任务、等待已启动任务结束并返回非零退出码。
- **日志落盘**：普通任务持续读取合并后的标准输出和错误输出，在保留控制台日志的同时写入 UTF-8 日志；长驻进程直接重定向合并输出到文件，不依赖 CLI 的日志读取线程，也不实时转发到控制台。日志独立于状态快照写入，不因每行输出重写 `state.conf`；异常退出可能丢失尚未刷新的尾部，长驻程序本身需使用适当的输出编码和刷新策略。
- **异常退出识别**：记录执行器 PID 和进程启动时间（可获取时），并在执行期间持有运行目录内的独占文件锁；PID 本身不足以确认进程身份。后续状态读取若发现未结束的运行，应先核验文件锁及执行器身份，不能将其他仍在执行的运行标为中断。确认原执行器已退出后，将遗留的 `RUNNING` 任务标记为 `INTERRUPTED`、尚未启动的任务标记为 `SKIPPED`，并保存运行中断状态；无法确认时保留原记录并提示状态不确定。
- **并发读取**：先完整读取并关闭状态文件，再解析 HOCON。Windows 读取句柄可能短暂阻止替换，允许最多 200 ms 的原子替换重试；不降级为非原子写入，持续失败仍明确报错。
- **查询方式**：`status` 默认查看开始时间最新的运行，显示整体状态、任务状态、退出码、失败原因，以及运行和每个任务的开始时间、结束时间与耗时；`--run <run-id>` 选择历史运行，`--list` 列出所有运行及其起止时间、耗时，`--log <任务名>` 读取选中运行的任务日志。按开始时间排序，不让较旧运行的晚完成覆盖较新记录；查询成功返回 `0`，不因历史任务失败而返回失败退出码。
- **时间展示**：起止时间使用 UTC ISO 8601，仅显示到秒；持久化时间戳仍保留原始精度。耗时自动选择单位：小于 1 秒显示整数 `ms`，小于 1 分钟显示 `s`，小于 1 小时显示 `m`，其余显示 `h`；秒、分钟和小时最多保留两位小数并去除末尾零，例如 `303ms`、`2.76s`、`1.5m`、`27h`。运行中按查询时刻计算已耗时并标注 `(running)`；未启动任务的起止时间与耗时显示 `-`。取消或中断记录中的结束时间可能是取消或恢复时刻，不能视为实际进程退出时间，其耗时加 `~` 标记为估计值；缺少时间或系统时钟倒退导致无法计算时显示 `-`。耗时由现有时间戳计算，不额外持久化。
- **环境检查**：`status --tools` 保留 Bash 和工具可用性检查，可配合 `--bash`；不执行版本命令或探测 Docker 等守护进程。所有模式支持 `--directory`。工具模式不能与运行查询参数混用。
- **查询与恢复边界**：读取历史、列表和日志不要求 Bash 或项目配置仍然存在；长驻进程的实时就绪检查与自定义停止命令使用启动时保存的 Bash 和脚本，Bash 不可用时不能确认就绪。旧版本未保存的运行无法回填。状态文件不允许 include，也不从环境变量解析额外数据。不自动断点续跑、接管或重启遗留进程；手动清理仅处理已记录且身份可确认的实例。后续若增加恢复执行，需校验命令及依赖是否变化，并明确幂等性与重跑策略。
- **本地数据边界**：运行记录与日志属于生成数据，应将 `.devpulse\` 排除在版本控制之外；不保存环境变量或凭据快照。任务输出可能包含敏感信息，用户应在共享日志前审核。首期不自动清理历史记录；大量历史检索或跨进程事务需求出现后再考虑 SQLite。

### 本地长驻进程管理（`DaemonManager.java`、`StopCommand.java`）

- **配置与启动**：任务可声明 `type: daemon`、必填的 `ready.cmd`、检查间隔和超时；`shutdownHook` 默认 false，可选 `stop` 与 `stopTimeout`。启动、检查和停止脚本按任务 ID 保存，进程 PID、启动时间、Bash 路径及停止配置通过独立 HOCON 文件原子持久化。无法记录身份或元数据时尽力终止尚未受管的实例。
- **依赖与并发**：普通依赖等待成功退出，长驻进程依赖等待就绪；就绪后释放执行槽，但继续持有进程及监控。`--jobs` 不限制已就绪进程的总数或探测进程。每个进程通过虚拟线程进行有时限的周期探测；意外退出或失去就绪状态使运行失败并停止启动新任务，不自动重启。
- **生命周期**：运行结束或可处理的退出时，仅清理开启 hook 的实例，按计划逆依赖顺序处理。未开启 hook 的实例在 CLI 返回后继续运行，监控随 CLI 结束；后续 `status` 详情按需核验存活及就绪，不存在独立后台调度器。就绪状态不是跨进程的恢复点。
- **清理与所有权**：`stop [TASK] --run <ID>` 使用已保存配置，默认选最新运行、可省略任务名清理全部实例。单实例清理使用独立锁，与运行状态写入分开；PID 和启动时间不匹配时不终止复用 PID 的其他进程，身份不确定时拒绝操作。停止命令接收实例身份环境变量，先执行有时限的停止，再尽力强制回收已确认的进程及可见后代；不按名称批量清理，不删除业务数据。
- **审阅与诊断**：静态审阅显示全部生命周期命令和时限，不执行探测。`status` 将历史启动结果和实时 `STARTING` / `READY` / `NOT_READY` / `EXITED` / `STOPPED` / `UNKNOWN` 分开显示；历史列表与日志读取不执行检查。日志、元数据与脚本属于本地生成数据，可能包含敏感内容，不能提交或未经审核共享。

## English

[Architecture index](../architecture.md) | [Usage](../../devpulse-core/README.md)

### Module boundaries

`devpulse-core` is a shared library: it registers no CLI, loads no plugins, reads no Pulse YAML, and registers no global shutdown hooks. It contains independent Java DAG and Bash workflow APIs; the former depends only on the JDK, while the latter uses Typesafe Config for HOCON run records.

### Java DAG definitions and scheduling

`DagWorkflow` stores immutable node definitions and topological order. Its Builder validates task identities and dependencies; construction uses iterative Kahn ordering to check unknown dependencies, matching types, and cycles. Each `DagRun` owns its execution state and schedules virtual threads using a ready queue and remaining dependency counts. Workers report `Update` / `Completed` messages to the coordinator, which updates state and emits events serially. `TaskKey<T>` and `TaskContext` restrict reads to type-matching direct dependency results.

Retries, per-attempt timeouts, and cancellation use cooperative interruption rather than force-killing threads. Final failures skip descendants while independent branches continue; states and results are in memory only. Detailed semantics, examples, and advanced-feature TODOs live in the module usage guide and must not be confused with the Bash failure policy.

### Bash component (`BashShell.java`)

- **Unified interpreter**: Use Bash on every OS and Git Bash on Windows. Report missing Bash explicitly; never fall back to `sh`, CMD, PowerShell, or WSL.
- **Discovery order**: An explicit `--bash` path takes priority, followed by PATH lookup. Also check Git installation locations on Windows and accept only Bash executables with Git installation markers to avoid WSL launchers.
- **Verification**: Verify that Bash actually starts using a short-lived subprocess, rather than checking file presence alone.
- **Invocation**: Use `ProcessBuilder` to run `bash --noprofile --norc -s --`. For batch tasks, write the complete UTF-8 script to standard input and close input; for daemon, readiness, and stop commands, redirect input from saved script files. Do not split commands on spaces or pass command text with `-c`, avoiding Windows command-line quoting altering quotes and variable expressions. This mode does not provide interactive input.
- **Environment and directory**: Set the working directory through `ProcessBuilder.directory()`. Inherit the parent environment, but remove `BASH_ENV`, `ENV`, and `BASH_FUNC_*` to avoid environment-selected startup files or imported user functions. Do not rely on aliases or user startup scripts.
- **Probe resource control**: Apply timeouts to verification and inventory, continuously read inventory output, and clean up probe processes.
- **Platform boundaries**: Shared Bash syntax does not make tools, paths, permissions, or OS capabilities identical. Git Bash is not a complete Linux environment.

### Environment fact collection (`EnvironmentGatherer.java`)

`EnvironmentGatherer` uses `BashShell` to look up a fixed tool list, checks root-level project markers, and returns `Facts` / `Tool` records containing the directory, OS, architecture, Java version, and Bash location. It neither reads project contents nor scans recursively or runs tool versions/health checks. Fact lists use defensive copies; prompt templates, encoding warnings, and clipboard output belong to the Gather command layer rather than core.

### Bash definitions, planning, and runtime

**Independent planning module**: The immutable `TaskDefinition`, `WorkflowDefinition`, and `TaskPlan` in `devpulse-core` depend only on the JDK rather than YAML, Typesafe Config, or the CLI. Task definitions contain commands, dependencies, and optional daemon settings; workflow definitions contain task lists and pipeline targets. `TaskPlan.create` validates the complete definition, expands transitive dependencies for a selected task or pipeline, and returns a topologically ordered plan without reading configuration, discovering Bash, executing commands, or writing run records.

`PlanningException` exposes format-independent error locations through `scope()`, `name()`, and `field()`. Pulse's `PulseConfiguration.plan` associates these with the original YAML file and available line numbers. Pulse's `TaskDetails` formats daemon terminal descriptions rather than placing them in core definitions.

**Independent execution runtime**: Core's `WorkflowRun` binds a plan and concurrency limit. Its blocking `execute()` may be called only once and returns the overall state and succeeded, failed, and skipped counts. `cancel()` may be called from another thread: cancellation before execution starts no tasks, repeated cancellation and cancellation after completion do not change terminal results, and external cancellation during execution interrupts the execution thread. Callers manage `RunHistory.Writer` with try-with-resources and decide whether to register a JVM shutdown hook; core registers no global hooks. Pulse maps success to exit code `0`, other results to `1`, and interruption to `130`.

`RunHistory` and `DaemonManager` also reside in core, preserving scheduling, failure policy, and version `1` HOCON record formats. The execution runtime uses Typesafe Config for these records but does not read task YAML or depend on picocli or the TUI. See the [module usage guide](../../devpulse-core/README.md) for embedded execution examples.

### Local task execution persistence (`RunHistory.java`)

**Approach**: Persist files within the project without introducing a database. `pulse` saves the plan before launching tasks and continuously persists task states and logs. Multiple independent runs may execute concurrently.

```text
.devpulse\
  runs\
    <run-id>\
      state.conf
      run.lock
      logs\
        <task-id>.log
      daemons\
        <task-id>.conf
        <task-id>-start.sh
        <task-id>-ready.sh
        <task-id>-stop.sh
        <task-id>.lock
```

- **Storage layout**: Generate a unique `run-id` for each actual execution and create a separate run directory under `.devpulse\runs` in the project configuration directory, preventing repeated or concurrent runs from overwriting each other. Use generated `task-id` values for log filenames rather than using task names as paths. `--dry-run` creates no run records.
- **State format**: Use UTF-8 HOCON for `state.conf`, reusing Typesafe Config for reading and writing. Record the format version, run ID, target, project directory, effective concurrency limit, overall state and start/end times, plus each task's ID, name, dependencies, state, start/end times, exit code, failure reason, and relative log path. Omit fields such as end time and exit code until available.
- **State semantics**: Distinguish task states `PENDING`, `RUNNING`, `READY`, `SUCCESS`, `FAILED`, `SKIPPED`, `CANCELLED`, and `INTERRUPTED`. `READY` records daemon readiness during that execution, not its eventual exit. Ordinary failures are `FAILED`; unstarted tasks are `SKIPPED`; explicitly cancelled batches are `CANCELLED`; unconfirmed results after executor exit are `INTERRUPTED`. Overall pipeline outcome is separate from subsequent live daemon state.
- **Write timing and consistency**: Save the initial plan before execution, persist `RUNNING` before starting a task, save its result after completion, and write the overall result when the run ends. Attempt to save cancellation state on explicit cancellation. Serialize state writes within each run, writing a temporary file in the same directory before atomically replacing `state.conf`. Report filesystems without atomic replacement support instead of falling back to direct overwrite. Do not start tasks if initialization fails. Persistence failures during execution must be reported, stop new task launches, wait for started tasks to finish, and return a nonzero exit code.
- **Log persistence**: Batch output is continuously read, printed, and saved as UTF-8. Daemon output is merged and redirected directly to a file without depending on CLI reader threads or live console forwarding. Logs are independent of snapshots rather than rewriting `state.conf` per line. Unexpected exits may lose buffered tails; daemon programs must select suitable output encoding and flushing.
- **Unexpected exit detection**: Record the executor PID and process start time when available, and hold an exclusive file lock in the run directory during execution; PID alone cannot establish process identity. When a later state read finds an unfinished run, check the file lock and executor identity before marking it interrupted, without modifying another live run. Once the original executor is confirmed to have exited, mark remaining `RUNNING` tasks as `INTERRUPTED`, unstarted tasks as `SKIPPED`, and persist the interrupted run state. If identity cannot be confirmed, retain the record and report uncertainty.
- **Concurrent reads**: Read the complete state file and close it before parsing HOCON. Windows read handles can briefly block replacement; allow up to 200 ms of atomic replacement retries. Never fall back to non-atomic writes, and report persistent failures explicitly.
- **Queries**: `status` defaults to the most recently started run and shows overall state, task states, exit codes, failure reasons, and start times, end times, and durations for both the run and every task. `--run <run-id>` selects a historical run, `--list` lists all runs with their start/end times and durations, and `--log <task-name>` reads a task log from the selected run. Sort by start time so an older run finishing later does not overwrite a newer record. Successful queries return `0`, even when the recorded execution failed.
- **Timing display**: Use UTC ISO 8601 timestamps displayed to seconds, preserving the original precision in persisted timestamps. Select duration units automatically: integer `ms` below one second, `s` below one minute, `m` below one hour, and `h` otherwise. Seconds, minutes, and hours use at most two decimal places with trailing zeros removed, for example `303ms`, `2.76s`, `1.5m`, and `27h`. For running work, calculate elapsed time at query time and append `(running)`. Show `-` for all timing fields of unstarted tasks. Cancellation or interruption end times may represent cancellation or recovery rather than actual process exit; prefix their estimated durations with `~`. Show `-` when missing timestamps or backward system-clock changes prevent calculation. Derive durations from existing timestamps without persisting additional fields.
- **Environment checks**: `status --tools` retains Bash and tool availability checks with optional `--bash`. Do not run version commands or probe daemons such as Docker. All modes support `--directory`; do not combine tool mode with run-query options.
- **Query and recovery boundaries**: Reading history, lists, and logs needs no Bash or project configuration. Live daemon readiness and custom stopping use recorded Bash and scripts; missing Bash prevents confirming readiness. Older unrecorded runs cannot be backfilled. State files cannot include resources or resolve additional environment data. Do not resume, take over, or restart orphaned processes; manual cleanup targets only recorded, identity-confirmed instances. Future resumption must validate definitions and establish idempotency/retry policies.
- **Local data boundaries**: Run records and logs are generated data; exclude `.devpulse\` from version control. Do not persist environment-variable or credential snapshots. Task output may contain sensitive information and must be reviewed before sharing. Do not automatically prune history initially; consider SQLite later if extensive historical queries or cross-process transactions become necessary.

### Local daemon management (`DaemonManager.java`, `StopCommand.java`)

- **Configuration and startup**: Tasks may declare `type: daemon`, required `ready.cmd`, check intervals, and timeouts. `shutdownHook` defaults to false, with optional `stop` and `stopTimeout`. Save launch, readiness, and stop scripts by task ID; atomically persist PID, process start time, Bash path, and stop settings in separate HOCON files. If identity or metadata cannot be recorded, attempt to terminate the untracked instance.
- **Dependencies and concurrency**: Batch prerequisites require successful exit; daemon prerequisites require readiness. Readiness releases an execution slot while retaining the process and monitoring it. `--jobs` does not limit ready daemons or probe processes. Each daemon uses a virtual thread for periodic, bounded checks. Unexpected exit or readiness loss fails the run and stops new tasks without automatic restart.
- **Lifecycle**: On completion or handled exit, clean up only hook-enabled instances in reverse plan dependency order. Other instances survive CLI exit, when monitoring ends. Later detailed `status` queries check liveness and readiness on demand; there is no separate background scheduler. Readiness is not a cross-process recovery checkpoint.
- **Cleanup and ownership**: `stop [TASK] --run <ID>` uses saved settings, defaults to the latest run, and allows omitting the task to clean all instances. Per-instance cleanup uses a separate lock independent of run-state writes. PID/start-time mismatches never terminate a reused PID; uncertain identity is refused. Stop commands receive instance identity environment variables. Execute bounded stopping, then attempt forced cleanup of confirmed processes and visible descendants. Do not kill by process name or delete business data.
- **Review and diagnostics**: Static review displays all lifecycle commands and timeouts without probing. `status` separates historical startup outcomes from live `STARTING` / `READY` / `NOT_READY` / `EXITED` / `STOPPED` / `UNKNOWN` states. Lists and log queries do not execute checks. Logs, metadata, and scripts are local generated data that may contain sensitive content; do not commit or share them without review.
