# Pulse 架构设计 / Pulse architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../../architecture.md) | [使用说明](../../../devpulse-plugins/pulse/README.md)

### 模块边界

Pulse 负责 YAML 配置适配、picocli 命令与终端展示；任务定义、计划、Bash 执行、运行历史和长驻进程管理由 [devpulse-core](../../devpulse-core/architecture.md) 提供。Pulse 将核心结果映射为 CLI 退出码，并负责 CLI 退出钩子。

### 配置格式与加载

- 任务配置仅支持 UTF-8 YAML 文件 `.devpulse.yaml`，不再读取 `.devpulse.conf`；迁移说明见[核心 CLI 使用指南](../../../devpulse-plugins/pulse/README.md)。
- 使用 SnakeYAML Engine 的 YAML 1.2 Core Schema 解析单个映射文档，限制文件大小为 1 MiB、集合嵌套为 64 层；拒绝重复键、非字符串键、自定义标签、锚点、别名和合并键，不构造任意 Java 对象。
- 将 YAML 节点转换为带文件和行号来源的 Typesafe Config 值，复用类型化读取和时长校验；不将 YAML 字符串再次按 HOCON 解析。
- 默认 `version: 1`、`parallelism: 4` 和空 `pipelines`；不做配置变量替换，不加载 JVM 系统属性或环境变量，命令中的变量保留给 Bash。
- 不支持 include 或远程资源加载；复杂逻辑由用户维护的本地脚本承载。
- `tasks` 是以任务名为键的映射，任务支持可选 `description`；`pipelines` 通过目标任务列表选择执行范围。声明顺序不代表依赖顺序。
- 配置错误应报告文件来源及可用的行号信息；字段类型、重复键、未知依赖和循环依赖均需在执行任何命令之前校验。根级、任务、就绪检查和流水线的未知字段均报错。
- `gather` 生成的 Prompt 与 `init` 生成的默认配置统一使用 YAML 示例。Jira 配置和内部运行记录继续使用 HOCON，不受任务配置迁移影响。

### 执行适配与失败策略

**实现步骤**：

- **解析配置**：按配置加载规则读取 YAML 配置，构造配置模型。
- **目标选择**：`pulse` 默认选择 `dev`；`pulse <名称>` 支持流水线或单任务。目标同时是任务名和流水线名时拒绝执行，要求用户消除重名。
- **构建有向无环图（DAG）**：将 `tasks` 映射中的每个任务视作一个节点（Vertex），将它们之间的 `needs` 依赖关系视作有向边（Edge）。
- **依赖补齐**：流水线的 `tasks` 声明最终目标，自动补齐全部传递依赖；共享依赖和重复目标在本次执行中只运行一次。列表顺序不代表依赖顺序。
- **拓扑排序与循环依赖检测**：采用 Kahn 算法或 DFS 算法检查依赖图。如果发现循环依赖（例如 A 依赖 B，B 又依赖 A），在启动任何命令前报错；执行时仅将所有依赖均已满足的任务加入就绪队列：普通任务成功退出，长驻进程通过就绪检查；在并发上限内调度。
- **底层进程控制器**：复用 Bash 基础组件拉起本地命令，工作目录为项目配置所在目录。合并错误流和标准流（`redirectErrorStream(true)`），普通任务以 UTF-8 持续读取并输出带任务名前缀的日志，长驻进程直接写入日志文件。工具必须已安装且能被 Bash 找到；执行权限与当前用户相同，不提供沙箱。
- **并发上限**：默认最多同时运行 4 个批任务或长驻进程启动等待；支持配置 `parallelism` 与 `pulse --jobs` 覆盖，优先级为命令行参数高于项目配置、高于默认值。取值必须为正整数，并在执行前校验；该限制不控制已就绪长驻进程数量、探测进程或外部任务内部的并发。
- **失败处理**：以 Bash 退出码判断任务结果；`0` 成功，非零失败。长脚本提前成功退出时，允许输入管道因 Bash 关闭标准输入而产生的写入 I/O 异常，不误判为失败；日志读取和持久化失败仍需报错。不隐式启用 `errexit` 或 `pipefail`，需要时由任务命令显式设置。观察到失败后停止启动新任务，等待所有已启动任务结束，返回非零退出码并汇总成功、失败和跳过数量。
- **命令选项**：`--directory` 指定项目目录，`--bash` 指定 Bash，`--jobs` 覆盖并发上限；`--dry-run` 校验完整配置并显示补齐依赖后的计划及命令，不探测 Bash、不启动任务。
- **执行边界**：支持非交互批任务与显式配置的本地长驻进程，不提供普通批任务超时、自动重启或常驻调度平台。取消时尽力终止批任务及可见后代；长驻进程是否清理由 `shutdownHook` 控制。不保证回收已脱离进程树的后台进程。

### 配置初始化模块（`InitCommand.java`）

**职责**：在项目目录生成可修改的 `.devpulse.yaml` 配置骨架，不扫描环境、不调用 AI、不执行任务，也不要求已安装 Bash。

- **默认配置**：UTF-8 编码的 YAML，包含 `version: 1`、`parallelism: 4`、两个仅使用 Bash `echo` 的示例任务，以及带有依赖关系的 `dev` 流水线。
- **目录选择**：默认使用当前工作目录；`init --directory <目录>` 指定其他已存在的项目目录，不自动创建目录。
- **覆盖保护**：默认仅创建新文件，配置已存在时拒绝写入并返回非零退出码；仅显式 `--force` 时允许覆盖普通文件，不跟随配置文件的符号链接。
- **结果反馈**：成功后打印配置路径和命令审核提醒；目录无效或文件写入失败时明确报错，不执行示例任务。

### 执行计划审阅模块（`ReviewCommand.java`、`ReviewTui.java`、`DagWidget.java`）

**职责**：静态校验任务或流水线的执行计划，在交互式 TUI 中供用户执行前审阅，不调用 AI。

- **目标与校验**：`review` 默认选择 `dev`，`review <名称>` 支持流水线或单任务。复用 `PulseConfiguration.load` 和 `PulseConfiguration.plan`，后者调用 core 的 `TaskPlan.create`；与 `pulse` 保持相同的完整配置校验、目标重名检查、循环依赖检测和传递依赖补齐规则。
- **TUI 与依赖展示**：使用 TamboUI 的 `tamboui-tui` 与 `tamboui-jline3-backend`，版本统一由 `devpulse-dependencies/pom.xml` 管理，不使用快照版本。左侧默认显示分层 DAG，可切换至按拓扑顺序排列的任务列表；右侧显示选中任务的直接前置依赖、计划内后继任务和完整 Bash 命令。DAG 中的编号对应任务列表顺序，长名称截断后可在详情中查看完整名称。
- **DAG 布局与高亮**：自定义 TamboUI Widget 按最长依赖路径的深度 `L` 从左到右分层，同层按拓扑顺序从上到下排列；共享任务仅绘制一个节点，每条直接依赖均保留指向后继节点的连线。跨层长连线从节点行之间绕过中间节点；交叉线可能共享显示单元格，不代表新增依赖，右侧详情提供精确的直接依赖关系。选中节点为黄色，全部传递前置依赖及其路径为绿色，全部传递后继及其路径为品红色；无关节点与连线保持灰色。不自动消除所有交叉。
- **计划信息与交互**：顶部显示目标、项目目录、任务数量、有效并发上限和颜色说明。上／下方向键选择任务，`Home`／`End` 选择首／末任务；左／右方向键水平平移 DAG，`w`／`s` 垂直平移，`r` 将视图定位到选中任务；`Tab` 切换 DAG 与任务列表，`PageUp`／`PageDown` 滚动右侧详情，`q` 退出。切换任务时自动定位图中节点并重置详情滚动位置；图形按视口裁剪，不覆盖相邻面板，长命令按终端显示宽度换行，控制字符以可见文本展示而不作为终端指令解释。依赖深度和拓扑顺序不代表固定执行批次或串行顺序，实际调度仍由依赖成功情况及并发上限决定。
- **终端生命周期与降级**：通过 JLine 检测标准输出终端；没有可用的交互终端（如重定向或管道）时自动保留纯文本计划，不读取交互按键。`--plain` 显式选择原有 ASCII 依赖列表及命令输出。交互模式使用备用屏幕和原始输入模式，通过 try-with-resources 及 TamboUI 的退出清理恢复屏幕、光标和终端输入状态。终端缩小时重新排版，小于 `60 × 16` 时提示放大窗口且仍允许退出。
- **命令选项**：`--directory` 指定项目配置目录，`--jobs` 仅覆盖审阅时显示的并发上限，不修改配置；与 `pulse` 使用相同的正整数约束与优先级。`--plain` 禁用 TUI。不提供 `--bash`，因为审阅不依赖 Bash。
- **执行边界与反馈**：不探测工具、不启动 Bash、不执行任务、不写入运行记录；有效计划及正常退出返回 `0`，配置、目标或 TUI 初始化／渲染无效时报告错误并返回非零退出码。静态校验成功不代表命令安全或工具可用。保留现有 `pulse --dry-run` 行为。

### 原有实施路线图

- 使用 SnakeYAML Engine 解析 YAML 任务配置，保留来源行号并复用 Typesafe Config 完成默认值合并与执行前校验。
- 手写基于 Kahn 的拓扑排序函数，确保能够处理复杂的相互依赖。
- 实现依赖就绪任务的并发调度，落实默认 4 个任务的并发上限，以及 `parallelism`、`--jobs` 的覆盖规则。
- 完善 `ProcessBuilder` 的异常处理，落实停止新任务、等待运行中任务结束的失败策略，不会引起系统崩溃。
- 使用 TamboUI 实现 `review` 交互式静态计划审阅，展示依赖关系、拓扑顺序、命令和并发上限，支持键盘导航与纯文本降级，不执行任务。
- 实现基于文件的运行状态与日志持久化、异常退出识别及历史查询，不包含自动断点续跑。
- 完成 `init` 默认配置生成与 `status` 本地工具健康状态展示，形成首版完整命令集合。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugins/pulse/README.md)

### Module boundaries

Pulse owns YAML adaptation, picocli commands, and terminal presentation. [devpulse-core](../../devpulse-core/architecture.md) provides definitions, planning, Bash execution, history, and daemon management. Pulse maps core results to CLI exit codes and owns CLI shutdown hooks.

### Configuration format and loading

- Task configuration supports only UTF-8 YAML in `.devpulse.yaml`; `.devpulse.conf` is no longer read. See the [Core CLI guide](../../../devpulse-plugins/pulse/README.md) for migration.
- Use SnakeYAML Engine's YAML 1.2 Core Schema to parse a single mapping document, limited to 1 MiB and 64 collection nesting levels. Reject duplicate keys, non-string keys, custom tags, anchors, aliases, and merge keys; never construct arbitrary Java objects.
- Convert YAML nodes to Typesafe Config values with file and line origins, reusing typed reads and duration validation without reparsing YAML strings as HOCON.
- Default to `version: 1`, `parallelism: 4`, and empty `pipelines`. Do not substitute configuration variables or load JVM system properties/environment variables; leave command variables to Bash.
- Do not support includes or remote resource loading. Keep complex logic in user-maintained local scripts.
- `tasks` is a mapping keyed by task name, with optional task `description`; `pipelines` select execution targets through task lists. Declaration order is not dependency order.
- Report configuration origins and line numbers when available. Validate field types, duplicate keys, unknown dependencies, and cycles before executing any command. Reject unknown root, task, readiness, and pipeline fields.
- Use YAML examples consistently in generated gather prompts and init configurations. Jira configuration and internal run records retain HOCON and are unaffected by task configuration migration.

### Execution adaptation and failure policy

**Implementation steps**:

- **Configuration**: Load YAML according to the configuration rules and construct configuration models.
- **Target selection**: `pulse` defaults to `dev`; `pulse <name>` accepts either a pipeline or a single task. Reject targets that name both a task and a pipeline and require the collision to be resolved.
- **DAG construction**: Treat each task in the `tasks` mapping as a vertex and each `needs` relationship as a directed edge.
- **Dependency expansion**: Pipeline `tasks` declare final targets. Include all transitive dependencies automatically and run shared dependencies or duplicate targets once per invocation. List order does not define dependency order.
- **Ordering and cycle detection**: Validate the dependency graph using Kahn's algorithm or DFS. Report cycles before starting commands. Only enqueue tasks whose dependencies are all satisfied: successful batch exit or successful daemon readiness. Schedule within the concurrency limit.
- **Process execution**: Reuse the Bash component with the project configuration directory as the working directory. Merge standard error and output using `redirectErrorStream(true)`. Continuously read UTF-8 batch output with task-name prefixes; redirect daemon output directly to log files. Tools must be installed and discoverable by Bash. Commands run with the current user's permissions; no sandbox is provided.
- **Concurrency**: Allow at most 4 concurrent batch tasks or daemon startup waits by default. Support `parallelism` and `pulse --jobs`, with CLI options overriding project configuration and defaults. Validate positive integers before execution. This does not limit ready daemons, probe processes, or concurrency inside external tasks.
- **Failure policy**: Use the Bash exit code: zero means success, nonzero means failure. When a long script exits successfully before consuming all input, tolerate input-write I/O errors caused by Bash closing stdin rather than misclassifying the task as failed; output reading and persistence failures must still be reported. Do not implicitly enable `errexit` or `pipefail`; commands may set them explicitly. Once failure is observed, stop launching new tasks, wait for started tasks to finish, return a nonzero exit code, and summarize succeeded, failed, and skipped counts.
- **Options**: `--directory` selects the project, `--bash` selects Bash, and `--jobs` overrides concurrency. `--dry-run` validates the whole configuration and prints the expanded plan and commands without discovering Bash or starting tasks.
- **Execution boundaries**: Support non-interactive batch tasks and explicitly configured local daemons, not ordinary batch timeouts, automatic restart, or a persistent scheduling platform. Cancellation attempts to terminate batches and visible descendants; daemon cleanup follows `shutdownHook`. Detached descendants are not guaranteed to be reclaimed.

### Configuration initializer (`InitCommand.java`)

**Responsibility**: Generate an editable `.devpulse.yaml` skeleton in the project directory. Do not scan the environment, call AI, execute tasks, or require an installed Bash.

- **Defaults**: UTF-8 YAML containing `version: 1`, `parallelism: 4`, two example tasks using only Bash `echo`, and a `dev` pipeline with a dependency relationship.
- **Directory selection**: Use the current working directory by default. `init --directory <directory>` selects another existing project directory; do not create directories automatically.
- **Overwrite protection**: Create new files only by default. Refuse existing configurations with a nonzero exit code. Allow overwriting regular files only with explicit `--force`; do not follow configuration-file symbolic links.
- **Feedback**: Print the configuration path and a command-review reminder on success. Clearly report invalid directories or write failures without executing example tasks.

### Execution plan review (`ReviewCommand.java`, `ReviewTui.java`, `DagWidget.java`)

**Responsibility**: Statically validate a task or pipeline execution plan and provide an interactive TUI for user review before execution, without calling AI.

- **Targets and validation**: `review` defaults to `dev`; `review <name>` accepts a pipeline or a single task. Reuse `PulseConfiguration.load` and `PulseConfiguration.plan`, which calls core's `TaskPlan.create`, for the same full configuration validation, target collision checks, cycle detection, and transitive dependency expansion as `pulse`.
- **TUI and dependencies**: Use TamboUI's `tamboui-tui` and `tamboui-jline3-backend`, with versions managed centrally in `devpulse-dependencies/pom.xml` and no snapshots. The left pane defaults to a layered DAG and can switch to a task list in topological order; the right pane shows the selected task's direct prerequisites, downstream tasks within the plan, and complete Bash command. DAG node numbers match task-list order; full names remain available in details when graph labels are truncated.
- **DAG layout and highlighting**: A custom TamboUI Widget lays out nodes left to right by longest dependency-path depth `L`, with nodes in each layer ordered top to bottom by topological order. Draw shared tasks only once and retain an arrow to the dependent node for every direct dependency. Route long cross-layer edges between node rows to avoid intermediate nodes. Crossing lines may share display cells without implying new dependencies; right-pane details provide the exact direct relationships. Highlight the selected node in yellow, all transitive prerequisites and their paths in green, and all transitive dependents and their paths in magenta; unrelated nodes and edges remain gray. Do not automatically eliminate all crossings.
- **Plan information and interaction**: Show the target, project directory, task count, effective concurrency limit, and color legend at the top. Up/Down selects tasks; `Home`/`End` selects the first/last task. Left/Right pans the DAG horizontally; `w`/`s` pans vertically; `r` centers the view on the selection. `Tab` switches between DAG and task list; `PageUp`/`PageDown` scrolls right-pane details; `q` quits. Center the graph and reset detail scrolling when selection changes. Clip graph output to its viewport without overwriting adjacent panes, wrap long commands by terminal display width, and display control characters as visible text rather than terminal instructions. Dependency depth and topological order are not fixed execution batches or a sequential schedule; actual scheduling still depends on successful prerequisites and the concurrency limit.
- **Terminal lifecycle and fallback**: Use JLine to detect the standard-output terminal. When no interactive terminal is available, such as with redirection or pipes, automatically retain plain-text output without reading interactive keys. `--plain` explicitly selects the original ASCII dependency list and command output. Interactive mode uses an alternate screen and raw input, with try-with-resources and TamboUI cleanup restoring the screen, cursor, and input state. Reflow on terminal resize; below `60 x 16`, prompt users to enlarge the window while still allowing quit.
- **Options**: `--directory` selects the project configuration directory. `--jobs` only overrides the displayed concurrency limit without modifying configuration, using the same positive-integer constraints and precedence as `pulse`. `--plain` disables the TUI. Do not provide `--bash`, since review does not require Bash.
- **Execution boundaries and feedback**: Do not probe tools, start Bash, execute tasks, or write run records. Return `0` for valid plans and normal quit; report invalid configuration, targets, or TUI initialization/rendering with a nonzero exit code. Successful static validation does not guarantee command safety or tool availability. Retain existing `pulse --dry-run` behavior.

### Original implementation roadmap

- Parse YAML task configuration with SnakeYAML Engine, preserve source lines, and reuse Typesafe Config to merge defaults and validate before execution.
- Implement Kahn-based dependency ordering for complex graphs.
- Implement dependency-ready concurrent scheduling with the default limit of 4 and `parallelism`/`--jobs` overrides.
- Handle `ProcessBuilder` failures by stopping new tasks and waiting for started tasks without crashing the application.
- Implement interactive static `review` using TamboUI, with dependencies, topological order, commands, concurrency limits, keyboard navigation, and plain-text fallback without executing tasks.
- Implement file-based run-state and log persistence, unexpected exit detection, and historical queries without automatic resume.
- Complete init configuration generation and status health reporting for the first release's full command set.
