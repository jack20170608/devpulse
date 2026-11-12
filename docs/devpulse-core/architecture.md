# devpulse-core 架构设计 / devpulse-core architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [使用说明](../../devpulse-core/README.md)

### 模块边界

`devpulse-core` 是共享库，不注册 CLI、不加载插件、不读取 Pulse YAML，也不注册全局退出钩子。`io.github.devpulse.core.dag` 提供通用图规划与调度，`io.github.devpulse.core.bash` 提供 Bash Adapter、进程管理、日志与 Bash 运行历史。两者共用一个调度器；DAG 核心仅依赖 JDK，Bash 模块用 Typesafe Config 维护 HOCON 运行记录。

### Java DAG 定义与调度

`DagGraph` 是唯一的不可变图定义，保存类型化身份与依赖，通过非递归 Kahn 算法校验依赖、类型与循环，并提供目标依赖闭包；Java 与 Bash 共用这些规则。`DagWorkflow` 绑定执行实现，通过 sealed Action 区分普通 `DagTask` 与 `DagDaemon`；可直接建图或复用已有 `DagGraph`，后者不重复规划。`DagWorkflow.select` 选取带依赖的子图并保留执行实现。`DagRun` 以就绪队列与未完成依赖计数调度虚拟线程；工作线程经 `Update` / `Completed` 报告，Daemon 监测经 `HealthChanged` 报告，协调线程串行更新状态和发送事件。`TaskKey<T>` 与 `TaskContext` 限制直接依赖的类型化结果读取。

普通任务成功退出、Daemon 就绪均释放依赖。Handle 提供结果与健康检测，不提供自动停止；就绪后释放启动槽位并继续监测。节点就绪结果与健康观测分别保存；失效跳过未启动后继、取消运行中后继，并将依赖它的已就绪 Daemon 标记为不可用，独立分支继续。结束前停止并等待监测退出、做最终健康检查，不清理长驻线程或进程。重试、超时与取消使用协作式中断，忽略中断可能阻止结束。详细契约、JVM 示例与后续路线统一维护在 [模块使用说明](../../devpulse-core/README.md)。

### Bash 基础组件（`BashShell.java`）

- **统一解释器**：所有 OS 均使用 Bash，Windows 使用 Git Bash；找不到可用 Bash 时明确报错，不回退到 `sh`、CMD、PowerShell 或 WSL。
- **定位顺序**：显式 `--bash` 路径优先，其次探测 PATH；Windows 额外检查 Git 安装位置，自动探测只接受具有 Git 安装标记的 Bash，避免误选 WSL 入口。
- **可用性验证**：通过短时子进程验证 Bash 能实际启动，而不是仅检查文件存在。
- **启动方式**：使用 `ProcessBuilder` 启动 `bash --noprofile --norc -s --`，普通任务通过标准输入写入完整 UTF-8 脚本后关闭输入；长驻进程、就绪和停止命令通过保存的脚本文件重定向标准输入。不自行按空格拆分，也不将命令文本放入 `-c` 参数，避免 Windows 命令行转义改变引号和变量表达式；不提供交互输入。
- **环境与目录**：使用 `ProcessBuilder.directory()` 设置工作目录，继承父进程环境，但移除 `BASH_ENV`、`ENV` 和 `BASH_FUNC_*`，避免自动执行环境指定的启动文件或导入用户函数。不依赖 alias 或用户启动脚本。
- **探测资源控制**：Bash 验证与环境扫描均设置超时，持续读取扫描输出，并清理探测进程。
- **平台边界**：统一 Bash 语法不代表工具、路径、文件权限和操作系统能力完全一致；Git Bash 不是完整 Linux 环境。

### 环境事实采集（`EnvironmentGatherer.java`）

`io.github.devpulse.core.environment.EnvironmentGatherer` 与 `BashShell` 同属 core 的环境包，供 Gather CLI 和 Pulse 的 `status --tools` 直接复用，不再维护独立的 gatherer Maven 模块。`gather()` 校验并规范化目录，通过固定的 Bash `command -v` 脚本获取工具位置，拒绝不完整盘点，并检查根目录项目标志；返回防御性复制的 `Facts` / `Tool` record。环境采集与 DAG、Bash 调度相互独立，不引入 CLI、提示词或剪贴板逻辑；不读取项目内容、不递归扫描、不采集环境变量值、不执行版本或健康检查。功能与使用契约见 [Core 使用说明](../../devpulse-core/README.md#环境事实采集)。

### Bash 定义、计划与运行时

**配置与图分离**：`BashTaskDefinition` 只描述 Bash 配置，不是可执行图。原 `TaskPlan` 与 `WorkflowDefinition` 已移除；Pulse 的 `PulsePlan` 保留配置装配、流水线目标解析与展示信息，将全部图校验与依赖补齐交给 `DagGraph`。执行方式与生命周期分离：DAG 不识别脚本语言，公开的 `BashTaskAdapter.task` / `daemon` 提供 `DagTask<Integer>` / `DagDaemon<ProcessHandle>`，可与 JVM 函数加入同一个 `DagWorkflow`，不通过语言枚举分派。

`PlanningException` 与 `TaskDetails` 属于 Pulse，分别负责 YAML 字段错误定位与终端说明；核心图错误只提供相关任务及原因。默认 Bash Adapter 不要求历史 Writer，批任务仅输出控制台，Daemon 日志与手动停止元数据存放在 `.devpulse\daemons\<id>`，不自动删除或停止资源。

**统一执行调度**：唯一调度器为 `DagRun`。`WorkflowRun` 接收装配完成的 `DagWorkflow`，只消费事件、记录历史并汇总结果，不建图或管理进程；通过显式生命周期识别 Daemon，不根据结果类型推断。`BashTaskAdapter` 管理批任务进程、输入输出、日志及中断回收，`DaemonManager` 提供长驻进程 Handle 与手动停止。取消和超时由 DAG 的协作式中断传递给 Adapter，不另设 Bash 取消调度器。单次 `execute()` 返回整体状态及成功、失败、跳过数量。调用方关闭历史 Writer 并管理 JVM 退出钩子；Pulse 将成功映射为 `0`，其他结果为 `1`，协调线程中断为 `130`。

`RunHistory` 从同一 `DagGraph` 创建历史，使用版本 `1` HOCON，单独保存 Daemon 健康、错误和观测时间，不覆盖节点就绪结果。实际启动前由录制型 Adapter 持久化 `RUNNING`，协调线程事件记录开始、完成与健康变化。普通失败只影响后继；启动、日志与持久化基础设施故障通过 `TaskContext.failRun` 或监听器异常取消运行。直接嵌入可使用 DAG 重试与超时，录制型 Adapter 在显式重试时追加日志；YAML 暂不暴露批任务重试或超时。该模块不读取 YAML、不依赖 picocli 或 TUI，不提供任意 Java 结果的通用序列化与恢复。嵌入示例见 [模块使用说明](../../devpulse-core/README.md)。

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
        <task-id>\
          <instance-id>.conf
          <instance-id>-start.sh
          <instance-id>-ready.sh
          <instance-id>-stop.sh
          <instance-id>.lock
```

- **存储布局**：每次实际执行生成唯一 `run-id`，在项目配置所在目录的 `.devpulse\runs` 下创建独立运行目录，避免多次或并发运行相互覆盖。使用生成的 `task-id` 作为日志文件名，不直接将任务名作为路径。`--dry-run` 不创建运行记录。
- **状态格式**：`state.conf` 使用 UTF-8 HOCON，复用 Typesafe Config 读写。记录格式版本、运行 ID、目标、项目目录、实际并发上限、运行状态与起止时间，以及各任务的 ID、名称、依赖、状态、起止时间、退出码、失败原因和相对日志路径。尚未产生的结束时间、退出码等字段不写入。
- **状态语义**：任务状态区分 `PENDING`、`RUNNING`、`READY`、`SUCCESS`、`FAILED`、`SKIPPED`、`CANCELLED`、`INTERRUPTED`；`READY` 记录长驻进程在该次执行中通过就绪检查，并非最终退出结果。正常失败为 `FAILED`，因失败策略未启动的任务为 `SKIPPED`，主动取消导致终止的批任务为 `CANCELLED`，执行器异常退出后无法确认结果的任务为 `INTERRUPTED`。整体运行状态表示流水线结果，与长驻进程后续的实时状态分开。
- **写入时机与一致性**：执行前保存计划，启动前持久化 `RUNNING`，结束后保存结果，健康变化另行记录，最后写入整体结果；取消时尽力保存状态。同次运行串行写入同目录临时文件，再原子替换 `state.conf`，不退化为直接覆盖。初始化失败不启动任务；执行中持久化失败停止新调度、取消未完成任务并返回非零退出码。
- **日志落盘**：普通任务持续读取合并后的标准输出和错误输出，在保留控制台日志的同时写入 UTF-8 日志；长驻进程直接重定向合并输出到文件，不依赖 CLI 的日志读取线程，也不实时转发到控制台。日志独立于状态快照写入，不因每行输出重写 `state.conf`；异常退出可能丢失尚未刷新的尾部，长驻程序本身需使用适当的输出编码和刷新策略。
- **异常退出识别**：记录执行器 PID 和进程启动时间（可获取时），并在执行期间持有运行目录内的独占文件锁；PID 本身不足以确认进程身份。后续状态读取若发现未结束的运行，应先核验文件锁及执行器身份，不能将其他仍在执行的运行标为中断。确认原执行器已退出后，将遗留的 `RUNNING` 任务标记为 `INTERRUPTED`、尚未启动的任务标记为 `SKIPPED`，并保存运行中断状态；无法确认时保留原记录并提示状态不确定。
- **并发读取**：先完整读取并关闭状态文件，再解析 HOCON。Windows 读取句柄可能短暂阻止替换，允许最多 200 ms 的原子替换重试；不降级为非原子写入，持续失败仍明确报错。
- **查询方式**：`status` 默认查看开始时间最新的运行，显示整体状态、任务状态、退出码、失败原因，以及运行和每个任务的开始时间、结束时间与耗时；`--run <run-id>` 选择历史运行，`--list` 列出所有运行及其起止时间、耗时，`--log <任务名>` 读取选中运行的任务日志。按开始时间排序，不让较旧运行的晚完成覆盖较新记录；查询成功返回 `0`，不因历史任务失败而返回失败退出码。
- **时间展示**：起止时间使用 UTC ISO 8601，仅显示到秒；持久化时间戳仍保留原始精度。耗时自动选择单位：小于 1 秒显示整数 `ms`，小于 1 分钟显示 `s`，小于 1 小时显示 `m`，其余显示 `h`；秒、分钟和小时最多保留两位小数并去除末尾零，例如 `303ms`、`2.76s`、`1.5m`、`27h`。运行中按查询时刻计算已耗时并标注 `(running)`；未启动任务的起止时间与耗时显示 `-`。取消或中断记录中的结束时间可能是取消或恢复时刻，不能视为实际进程退出时间，其耗时加 `~` 标记为估计值；缺少时间或系统时钟倒退导致无法计算时显示 `-`。耗时由现有时间戳计算，不额外持久化。
- **环境检查**：`status --tools` 保留 Bash 和工具可用性检查，可配合 `--bash`；不执行版本命令或探测 Docker 等守护进程。所有模式支持 `--directory`。工具模式不能与运行查询参数混用。
- **查询与恢复边界**：读取历史、列表和日志不要求 Bash 或项目配置仍然存在；长驻进程的实时就绪检查与自定义停止命令使用启动时保存的 Bash 和脚本，Bash 不可用时不能确认就绪。旧版本未保存的运行无法回填。状态文件不允许 include，也不从环境变量解析额外数据。不自动断点续跑、接管或重启遗留进程；手动清理仅处理已记录且身份可确认的实例。后续若增加恢复执行，需校验命令及依赖是否变化，并明确幂等性与重跑策略。
- **本地数据边界**：运行记录与日志属于生成数据，应将 `.devpulse\` 排除在版本控制之外；不保存环境变量或凭据快照。任务输出可能包含敏感信息，用户应在共享日志前审核。首期不自动清理历史记录；大量历史检索或跨进程事务需求出现后再考虑 SQLite。

### 本地长驻进程管理（`DaemonManager.java`、`StopCommand.java`）

- **配置与启动**：声明 `type: daemon`、必填 `ready.cmd`，可选 `stop` 与 `stopTimeout`；`shutdownHook` 已移除。Pulse 将监测参数直接解析为 DAG `DaemonOptions`，Bash 配置不保存副本。Adapter 从启动上下文读取实际策略；每次启动按独立实例 ID 保存脚本、尝试编号、进程身份、Bash 路径、实际策略和停止配置，重试不覆盖旧实例，任务日志追加。无法记录身份或元数据时尽力终止尚未受管的实例。
- **依赖与并发**：`DaemonManager.start` 返回 Handle，核心等待就绪、释放执行槽并继续监测。`--jobs` 不限制已就绪进程或探测进程数量。意外退出或失去就绪仅取消受影响分支，独立分支继续，不自动重启。
- **生命周期**：成功、失败、取消或就绪超时不清理已记录实例，用户自行调用 `stop`。监测随运行结束；`status` 按需核验存活及就绪，没有独立后台调度器。历史就绪、健康变化和实时状态分别展示；就绪不是跨进程恢复点。
- **清理与所有权**：`stop [TASK] --run <ID>` 默认选最新运行，任务级停止处理该任务全部实例，省略任务名按逆依赖顺序处理全部任务；`stop TASK --instance <实例ID>` 只停止指定实例。每个实例使用独立锁，核验 PID 和启动时间；身份不确定时拒绝操作，单个清理失败仍继续处理其他实例并汇报失败。停止命令接收 `DEVPULSE_DAEMON_INSTANCE_ID` 等身份变量，先执行有时限的停止，再尽力强制回收可确认的进程及可见后代；不按名称批量清理，不删除业务数据。旧平铺单实例记录仍可查询和停止。
- **审阅与诊断**：静态审阅显示生命周期命令及实际 DAG 时限，不执行探测。`status` 展示所有实例 ID、尝试编号、PID 和实时状态，与历史节点就绪结果分开。Bash 退出码由 Adapter 明确记录，不将 Java 整数结果当作退出码；汇总终端文案由 Pulse 输出。历史列表与日志读取不执行检查。日志、元数据与脚本属于本地生成数据，可能包含敏感内容，不能提交或未经审核共享。

## English

[Architecture index](../architecture.md) | [Usage](../../devpulse-core/README.md)

### Module boundaries

`devpulse-core` is a shared library: it registers no CLI, loads no plugins, reads no Pulse YAML, and registers no global shutdown hooks. `io.github.devpulse.core.dag` provides general graph planning and scheduling; `io.github.devpulse.core.bash` provides the Bash Adapter, process management, logs, and Bash run history. Both share one scheduler; the DAG core is JDK-only and the Bash module uses Typesafe Config for HOCON run records.

### Java DAG definitions and scheduling

`DagGraph` is the single immutable graph definition, storing typed identities and dependencies, validating dependencies, types, and cycles using iterative Kahn ordering, and expanding target dependency closure. Java and Bash share these rules. `DagWorkflow` binds execution actions, with sealed Actions distinguishing ordinary `DagTask` and `DagDaemon`; it can build a graph or reuse an existing `DagGraph` without repeating planning. `DagWorkflow.select` retains execution actions when selecting a dependency-closed subgraph. `DagRun` schedules virtual threads using a ready queue and remaining dependency counts. Workers report `Update` / `Completed`; daemon monitors report `HealthChanged`. The coordinator serially updates state and emits events. `TaskKey<T>` and `TaskContext` restrict typed reads to direct dependencies.

Successful batch exit and daemon readiness release dependencies. Handles provide results and health checks, not automatic stopping. Readiness releases startup slots while monitoring continues. Readiness outcomes and health observations are separate; health loss skips unstarted descendants, cancels running descendants, and marks dependent ready daemons unavailable while independent branches continue. Before completion, stop/join monitors and perform final health checks without cleaning up long-lived threads/processes. Retries, timeouts, and cancellation are cooperative; ignoring interruption can prevent completion. Contracts, a JVM example, and the roadmap live in the [module usage guide](../../devpulse-core/README.md).

### Bash component (`BashShell.java`)

- **Unified interpreter**: Use Bash on every OS and Git Bash on Windows. Report missing Bash explicitly; never fall back to `sh`, CMD, PowerShell, or WSL.
- **Discovery order**: An explicit `--bash` path takes priority, followed by PATH lookup. Also check Git installation locations on Windows and accept only Bash executables with Git installation markers to avoid WSL launchers.
- **Verification**: Verify that Bash actually starts using a short-lived subprocess, rather than checking file presence alone.
- **Invocation**: Use `ProcessBuilder` to run `bash --noprofile --norc -s --`. For batch tasks, write the complete UTF-8 script to standard input and close input; for daemon, readiness, and stop commands, redirect input from saved script files. Do not split commands on spaces or pass command text with `-c`, avoiding Windows command-line quoting altering quotes and variable expressions. This mode does not provide interactive input.
- **Environment and directory**: Set the working directory through `ProcessBuilder.directory()`. Inherit the parent environment, but remove `BASH_ENV`, `ENV`, and `BASH_FUNC_*` to avoid environment-selected startup files or imported user functions. Do not rely on aliases or user startup scripts.
- **Probe resource control**: Apply timeouts to verification and inventory, continuously read inventory output, and clean up probe processes.
- **Platform boundaries**: Shared Bash syntax does not make tools, paths, permissions, or OS capabilities identical. Git Bash is not a complete Linux environment.

### Environment fact collection (`EnvironmentGatherer.java`)

`io.github.devpulse.core.environment.EnvironmentGatherer` shares core's environment package with `BashShell`, directly reused by the Gather CLI and Pulse's `status --tools` without a separate gatherer Maven module. `gather()` validates and normalizes directories, runs a fixed Bash `command -v` inventory, rejects incomplete results, and checks root-level project markers. It returns `Facts` / `Tool` records with defensive list copies. Collection remains independent of DAG and Bash scheduling, introducing no CLI, prompts, or clipboard logic. It does not read project contents, scan recursively, collect environment variable values, or run version/health checks. See the [Core usage guide](../../devpulse-core/README.md#environment-fact-collection) for functionality and contracts.

### Bash definitions, planning, and runtime

**Configuration and graph are separate**: `BashTaskDefinition` describes Bash configuration rather than an executable graph. Former `TaskPlan` and `WorkflowDefinition` are removed. Pulse's `PulsePlan` retains configuration assembly, pipeline target resolution, and presentation metadata, delegating all graph validation and dependency expansion to `DagGraph`. Execution mode and lifecycle are separate: DAG does not recognize script languages. Public `BashTaskAdapter.task` / `daemon` supply `DagTask<Integer>` / `DagDaemon<ProcessHandle>`, composable with JVM functions in one `DagWorkflow` without language-enum dispatch.

`PlanningException` and `TaskDetails` belong to Pulse, handling YAML field locations and terminal descriptions respectively; core graph errors only identify the affected task and reason. The default Bash Adapter needs no history Writer. Batches only print to the console; daemon logs and manual-stop metadata live under `.devpulse\daemons\<id>` without automatic file removal or resource stopping.

**Unified execution scheduling**: `DagRun` is the only scheduler. `WorkflowRun` accepts an assembled `DagWorkflow`, consuming events, recording history, and summarizing results without constructing graphs or managing processes. Daemons are identified by explicit lifecycle rather than result types. `BashTaskAdapter` handles batch processes, input/output, logs, and interruption cleanup; `DaemonManager` provides daemon Handles and manual stopping. DAG cooperative interruption carries cancellation and timeouts to the Adapter without a separate Bash cancellation scheduler. Single-use `execute()` returns the overall state and succeeded, failed, and skipped counts. Callers close history Writers and manage JVM shutdown hooks. Pulse maps success to `0`, other results to `1`, and coordinator interruption to `130`.

`RunHistory` creates history from the same `DagGraph`, using version `1` HOCON with separate daemon health/error/observation fields rather than overwriting readiness. A recording Adapter persists `RUNNING` before actual startup; coordinator events persist starts, completion, and health transitions. Ordinary faults affect descendants only; startup, logging, and persistence infrastructure faults cancel the run through `TaskContext.failRun` or listener exceptions. Embedded callers can use DAG retries/timeouts, with explicit retries appending logs; YAML does not yet expose batch retry/timeout settings. The module reads no YAML and depends on neither picocli nor the TUI, and offers no general Java result serialization or recovery. See the [module usage guide](../../devpulse-core/README.md) for examples.

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
        <task-id>\
          <instance-id>.conf
          <instance-id>-start.sh
          <instance-id>-ready.sh
          <instance-id>-stop.sh
          <instance-id>.lock
```

- **Storage layout**: Generate a unique `run-id` for each actual execution and create a separate run directory under `.devpulse\runs` in the project configuration directory, preventing repeated or concurrent runs from overwriting each other. Use generated `task-id` values for log filenames rather than using task names as paths. `--dry-run` creates no run records.
- **State format**: Use UTF-8 HOCON for `state.conf`, reusing Typesafe Config for reading and writing. Record the format version, run ID, target, project directory, effective concurrency limit, overall state and start/end times, plus each task's ID, name, dependencies, state, start/end times, exit code, failure reason, and relative log path. Omit fields such as end time and exit code until available.
- **State semantics**: Distinguish task states `PENDING`, `RUNNING`, `READY`, `SUCCESS`, `FAILED`, `SKIPPED`, `CANCELLED`, and `INTERRUPTED`. `READY` records daemon readiness during that execution, not its eventual exit. Ordinary failures are `FAILED`; unstarted tasks are `SKIPPED`; explicitly cancelled batches are `CANCELLED`; unconfirmed results after executor exit are `INTERRUPTED`. Overall pipeline outcome is separate from subsequent live daemon state.
- **Write timing and consistency**: Persist the plan, `RUNNING` before startup, completion outcomes, separate health transitions, and the overall result. Attempt to persist cancellation. Serialize writes to same-directory temporary files and atomically replace `state.conf`; never fall back to overwrite. Initialization failure starts no tasks; execution-time persistence faults stop new scheduling, cancel unfinished tasks, and return a nonzero exit code.
- **Log persistence**: Batch output is continuously read, printed, and saved as UTF-8. Daemon output is merged and redirected directly to a file without depending on CLI reader threads or live console forwarding. Logs are independent of snapshots rather than rewriting `state.conf` per line. Unexpected exits may lose buffered tails; daemon programs must select suitable output encoding and flushing.
- **Unexpected exit detection**: Record the executor PID and process start time when available, and hold an exclusive file lock in the run directory during execution; PID alone cannot establish process identity. When a later state read finds an unfinished run, check the file lock and executor identity before marking it interrupted, without modifying another live run. Once the original executor is confirmed to have exited, mark remaining `RUNNING` tasks as `INTERRUPTED`, unstarted tasks as `SKIPPED`, and persist the interrupted run state. If identity cannot be confirmed, retain the record and report uncertainty.
- **Concurrent reads**: Read the complete state file and close it before parsing HOCON. Windows read handles can briefly block replacement; allow up to 200 ms of atomic replacement retries. Never fall back to non-atomic writes, and report persistent failures explicitly.
- **Queries**: `status` defaults to the most recently started run and shows overall state, task states, exit codes, failure reasons, and start times, end times, and durations for both the run and every task. `--run <run-id>` selects a historical run, `--list` lists all runs with their start/end times and durations, and `--log <task-name>` reads a task log from the selected run. Sort by start time so an older run finishing later does not overwrite a newer record. Successful queries return `0`, even when the recorded execution failed.
- **Timing display**: Use UTC ISO 8601 timestamps displayed to seconds, preserving the original precision in persisted timestamps. Select duration units automatically: integer `ms` below one second, `s` below one minute, `m` below one hour, and `h` otherwise. Seconds, minutes, and hours use at most two decimal places with trailing zeros removed, for example `303ms`, `2.76s`, `1.5m`, and `27h`. For running work, calculate elapsed time at query time and append `(running)`. Show `-` for all timing fields of unstarted tasks. Cancellation or interruption end times may represent cancellation or recovery rather than actual process exit; prefix their estimated durations with `~`. Show `-` when missing timestamps or backward system-clock changes prevent calculation. Derive durations from existing timestamps without persisting additional fields.
- **Environment checks**: `status --tools` retains Bash and tool availability checks with optional `--bash`. Do not run version commands or probe daemons such as Docker. All modes support `--directory`; do not combine tool mode with run-query options.
- **Query and recovery boundaries**: Reading history, lists, and logs needs no Bash or project configuration. Live daemon readiness and custom stopping use recorded Bash and scripts; missing Bash prevents confirming readiness. Older unrecorded runs cannot be backfilled. State files cannot include resources or resolve additional environment data. Do not resume, take over, or restart orphaned processes; manual cleanup targets only recorded, identity-confirmed instances. Future resumption must validate definitions and establish idempotency/retry policies.
- **Local data boundaries**: Run records and logs are generated data; exclude `.devpulse\` from version control. Do not persist environment-variable or credential snapshots. Task output may contain sensitive information and must be reviewed before sharing. Do not automatically prune history initially; consider SQLite later if extensive historical queries or cross-process transactions become necessary.

### Local daemon management (`DaemonManager.java`, `StopCommand.java`)

- **Configuration and startup**: Declare `type: daemon`, required `ready.cmd`, and optional `stop`/`stopTimeout`; `shutdownHook` is removed. Pulse parses monitoring policy directly into DAG `DaemonOptions`, without a copy in Bash configuration. The Adapter reads actual policy from startup context. Each startup saves scripts, attempt number, process identity, Bash path, actual policy, and stop settings under an independent instance ID. Retries do not overwrite earlier instances; task logs append. If identity or metadata cannot be recorded, attempt to terminate the untracked instance.
- **Dependencies and concurrency**: `DaemonManager.start` returns a Handle; the core waits for readiness, releases execution slots, and monitors health. `--jobs` does not limit ready daemons or probes. Unexpected exit/readiness loss cancels affected branches only; independent branches continue, without automatic restart.
- **Lifecycle**: Success, failure, cancellation, and readiness timeouts do not clean up recorded instances; users invoke `stop`. Monitoring ends with the run; `status` checks liveness/readiness on demand without a separate scheduler. Historical readiness, health transitions, and live observations are distinct. Readiness is not a cross-process checkpoint.
- **Cleanup and ownership**: `stop [TASK] --run <ID>` defaults to the latest run. Task-level stopping handles every instance of that task; omission processes all tasks in reverse dependency order. `stop TASK --instance <instance-id>` targets one instance. Each instance has a separate lock and PID/start-time identity checks. Uncertain identity is refused; failed cleanup does not prevent processing other instances and returns diagnostics. Stop commands receive `DEVPULSE_DAEMON_INSTANCE_ID` and other identity variables. Execute bounded stopping followed by best-effort forced cleanup of confirmed processes and visible descendants; do not kill by process name or delete business data. Legacy flat single-instance records remain queryable and stoppable.
- **Review and diagnostics**: Static review displays lifecycle commands and actual DAG limits without probing. `status` shows every instance ID, attempt, PID, and live state separately from historical node readiness. The Adapter explicitly records Bash exit codes without interpreting integer Java results as exit codes; Pulse prints summary text. Lists and log queries do not execute checks. Logs, metadata, and scripts are local generated data that may contain sensitive content; do not commit or share them without review.
