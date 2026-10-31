# devpulse Java 实现基本思路与架构方案

[中文](#中文) | [English](#english)

## 中文

本项目旨在构建一个轻量、高效、具备 AI 协同能力的本地 DevOps 智能调度工具箱。

技术栈、语言使用原则、构建打包、资源管理和测试约束统一维护在 [Copilot 仓库指令](.github/copilot-instructions.md) 中；本文描述功能行为、核心架构与开发路线图。

### 一、配置格式与加载

- 仅支持 HOCON 格式的 `.devpulse.conf`，不支持 YAML。
- 显式解析项目的 `.devpulse.conf`，通过 `withFallback` 合并内置 `reference.conf` 默认值，再调用 `resolve` 解析变量引用；不自动加载无关的 JVM 系统属性。
- 使用 Typesafe Config 的类型化读取 API 构造配置模型。
- `include` 仅允许本地文件或 classpath 资源，不允许远程 URL；实现时需限制包含资源的加载行为，而不是仅约定用户不写远程地址。
- 支持相对文件和显式 `file`、`classpath` include；可选缺失 include 被忽略，`required` 缺失时报错。拒绝 URL 和 UNC 网络路径，并检测 include 循环。
- 配置错误应报告文件来源及可用的行号信息；字段类型、重复任务名、未知依赖和循环依赖均需在执行任何命令之前校验。
- 工具和流水线的未知字段应报错，防止拼写错误被静默忽略；根级可定义用于 HOCON 变量引用的辅助字段。
- `gather` 生成的 Prompt 与 `init` 生成的默认配置统一使用 HOCON 示例。

### 二、核心架构设计

系统包含命令入口、资产盘点模块、可复用的 Bash 基础组件、调度引擎与配置初始化模块，职责清晰，方便后续解耦和扩展：

```text
用户终端输入（CLI Input） -> devpulse 主入口类 -> 分发至对应子模块
```

#### 1. 主入口类（`DevPulseCLI.java`）

**职责**：负责接收终端传入的参数（如 `gather`、`pulse`），进行基本的全局校验。

#### 2. 资产自动盘点模块（`GatherCommand.java`）

**职责**：收集本地系统的客观环境事实，按所选语言组装 AI 提示词（默认中英文对照），输出到终端并默认复制到剪贴板。

**实现步骤**：

- **环境与工具盘点**：记录 OS、架构、Java 版本、Bash 路径，通过 Bash 的 `command -v` 检查固定工具清单，不执行工具版本或健康检查。
- **项目特征嗅探**：仅检查项目根目录中关键指纹是否存在（如 `package.json`、`pom.xml`、`Dockerfile`、`.git`），不读取文件内容、不递归扫描目录。
- **采集边界**：环境变量仅用于 Bash 定位及工具查找，不将环境变量值或凭据加入提示词。提示词包含项目路径和工具位置，用户发送给外部 AI 前应审核。
- **提示词输出**：生成包含本地事实、HOCON 示例、Bash 执行约束和命令审核提醒的 Markdown；支持中文、英文或中英双语，双语模式保留同页面语言导航，单语言模式仅包含对应语言章节，不显示双语导航。本地事实中的路径、工具名等数据及 HOCON 示例保持原样；信息不足时要求 AI 声明假设，不捏造任务。
- **编码警告**：提示词输出前检查 Java 控制台的 `Console.charset()`；无控制台时检查 `System.out.charset()`。检测到非 UTF-8 时向标准错误输出英文警告，包含检测到的编码和 Git Bash 的 `Options > Text > Character set > UTF-8` 设置提示，不改变语言、输出编码、剪贴板内容或成功退出码。该检查反映 Java 检测到的编码，不能可靠读取 Git Bash 窗口的实际显示字符集；重定向输出时也可能触发，不代表已经确认发生乱码。
- **剪贴板控制**：默认通过 AWT 写入剪贴板；无图形环境、剪贴板忙或不可访问时提示警告，保留标准输出，盘点仍成功。
- **命令选项**：`gather --directory <目录>` 指定盘点位置，`--bash <路径>` 指定 Bash，`--no-clipboard` 禁用剪贴板复制；`--language zh|en|both` 同时控制终端与剪贴板中的提示词语言，默认 `both`，不支持的取值在盘点前拒绝。目录无效、Bash 缺失或扫描失败时返回非零退出码。遇到中文终端显示乱码时可使用 `gather --language en` 避开提示词中的中文；该选项不修复终端编码，也不翻译本地事实数据。

#### 3. Bash 基础组件（`BashShell.java`）

- **统一解释器**：所有 OS 均使用 Bash，Windows 使用 Git Bash；找不到可用 Bash 时明确报错，不回退到 `sh`、CMD、PowerShell 或 WSL。
- **定位顺序**：显式 `--bash` 路径优先，其次探测 PATH；Windows 额外检查 Git 安装位置，自动探测只接受具有 Git 安装标记的 Bash，避免误选 WSL 入口。
- **可用性验证**：通过短时子进程验证 Bash 能实际启动，而不是仅检查文件存在。
- **启动方式**：使用 `ProcessBuilder` 启动 `bash --noprofile --norc -s --`，通过标准输入写入完整 UTF-8 脚本后关闭输入，不自行按空格拆分。避免 Windows 命令行参数转义改变引号和变量表达式；此模式不提供交互输入。
- **环境与目录**：使用 `ProcessBuilder.directory()` 设置工作目录，继承父进程环境，但移除 `BASH_ENV`、`ENV` 和 `BASH_FUNC_*`，避免自动执行环境指定的启动文件或导入用户函数。不依赖 alias 或用户启动脚本。
- **探测资源控制**：Bash 验证与环境扫描均设置超时，持续读取扫描输出，并清理探测进程。
- **平台边界**：统一 Bash 语法不代表工具、路径、文件权限和操作系统能力完全一致；Git Bash 不是完整 Linux 环境。

#### 4. 图拓扑智能调度引擎（`PulseCommand.java`）

**职责**：读取用户存放的 `.devpulse.conf` 配置文件，智能计算依赖顺序，拉起本地系统进程执行。

**实现步骤**：

- **解析配置**：按配置加载规则读取 HOCON 配置，构造配置模型。
- **目标选择**：`pulse` 默认选择 `dev`；`pulse <名称>` 支持流水线或单任务。目标同时是任务名和流水线名时拒绝执行，要求用户消除重名。
- **构建有向无环图（DAG）**：将 `tools` 列表中的每个工具视作一个节点（Vertex），将它们之间的 `needs` 依赖关系视作有向边（Edge）。
- **依赖补齐**：流水线的 `tasks` 声明最终目标，自动补齐全部传递依赖；共享依赖和重复目标在本次执行中只运行一次。列表顺序不代表依赖顺序。
- **拓扑排序与循环依赖检测**：采用 Kahn 算法或 DFS 算法检查依赖图。如果发现循环依赖（例如 A 依赖 B，B 又依赖 A），在启动任何命令前报错；执行时仅将所有依赖均已成功的任务加入就绪队列，并在并发上限内调度。
- **底层进程控制器**：复用 Bash 基础组件拉起本地命令，工作目录为项目配置所在目录。合并错误流和标准流（`redirectErrorStream(true)`），以 UTF-8 持续读取并输出带任务名前缀的日志。工具必须已安装且能被 Bash 找到；执行权限与当前用户相同，不提供沙箱。
- **并发上限**：默认最多同时运行 4 个任务；支持配置 `parallelism` 与 `pulse --jobs` 覆盖，优先级为命令行参数高于项目配置、高于默认值。取值必须为正整数，并在执行前校验；该限制不控制外部任务内部的并发。
- **失败处理**：以 Bash 退出码判断任务结果；`0` 成功，非零失败。不隐式启用 `errexit` 或 `pipefail`，需要时由任务命令显式设置。观察到失败后停止启动新任务，等待所有已启动任务结束，返回非零退出码并汇总成功、失败和跳过数量。
- **命令选项**：`--directory` 指定项目目录，`--bash` 指定 Bash，`--jobs` 覆盖并发上限；`--dry-run` 校验完整配置并显示补齐依赖后的计划及命令，不探测 Bash、不启动任务。
- **执行边界**：仅支持前台、非交互批处理任务，不提供后台服务生命周期管理或任务超时。取消时尽力终止已知进程及可见后代，不保证终止已脱离进程树的后台进程。

#### 5. 配置初始化模块（`InitCommand.java`）

**职责**：在项目目录生成可修改的 `.devpulse.conf` 配置骨架，不扫描环境、不调用 AI、不执行任务，也不要求已安装 Bash。

- **默认配置**：UTF-8 编码的 HOCON，包含 `version = 1`、`parallelism = 4`、两个仅使用 Bash `echo` 的示例工具，以及带有依赖关系的 `dev` 流水线。
- **目录选择**：默认使用当前工作目录；`init --directory <目录>` 指定其他已存在的项目目录，不自动创建目录。
- **覆盖保护**：默认仅创建新文件，配置已存在时拒绝写入并返回非零退出码；仅显式 `--force` 时允许覆盖普通文件，不跟随配置文件的符号链接。
- **结果反馈**：成功后打印配置路径和命令审核提醒；目录无效或文件写入失败时明确报错，不执行示例任务。

#### 6. 本地任务执行状态持久化（待实现）

**方案**：首期采用项目内文件持久化，不引入数据库。当前执行状态仅保存在内存中，日志仅输出到控制台；以下为计划实现的行为。

```text
.devpulse\
  runs\
    <run-id>\
      state.conf
      logs\
        <task-id>.log
```

- **存储布局**：每次实际执行生成唯一 `run-id`，在项目配置所在目录的 `.devpulse\runs` 下创建独立运行目录，避免多次或并发运行相互覆盖。使用生成的 `task-id` 作为日志文件名，不直接将任务名作为路径。`--dry-run` 不创建运行记录。
- **状态格式**：`state.conf` 使用 UTF-8 HOCON，复用 Typesafe Config 读写。记录格式版本、运行 ID、目标、项目目录、实际并发上限、运行状态与起止时间，以及各任务的 ID、名称、依赖、状态、起止时间、退出码、失败原因和相对日志路径。尚未产生的结束时间、退出码等字段不写入。
- **状态语义**：任务状态区分 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`SKIPPED`、`CANCELLED`、`INTERRUPTED`；正常失败为 `FAILED`，因失败策略未启动的任务为 `SKIPPED`，主动取消导致终止的任务为 `CANCELLED`，执行器异常退出后无法确认结果的任务为 `INTERRUPTED`。运行记录也保存整体状态，不能将未完成记录视为成功。
- **写入时机与一致性**：执行前保存初始计划，启动任务前持久化 `RUNNING`，任务结束后保存结果，运行结束时写入整体结果；主动取消时尽力保存取消状态。同次运行的状态写入串行化，先写同目录临时文件，再通过原子替换更新 `state.conf`。文件系统不支持原子替换时明确报错，不退化为直接覆盖。初始化失败时不启动任务；执行中的持久化失败应明确报错、停止启动新任务、等待已启动任务结束并返回非零退出码。
- **日志落盘**：持续读取合并后的标准输出和错误输出，在保留现有控制台日志的同时写入对应任务的 UTF-8 日志文件。日志独立于状态快照写入，不因每行输出重写 `state.conf`；异常退出可能丢失尚未刷新的日志尾部。
- **异常退出识别**：记录执行器 PID 和进程启动时间（可获取时），并在执行期间持有运行目录内的独占文件锁；PID 本身不足以确认进程身份。后续状态读取若发现未结束的运行，应先核验文件锁及执行器身份，不能将其他仍在执行的运行标为中断。确认原执行器已退出后，将遗留的 `RUNNING` 任务标记为 `INTERRUPTED`、尚未启动的任务标记为 `SKIPPED`，并保存运行中断状态；无法确认时保留原记录并提示状态不确定。
- **查询与恢复边界**：首期支持读取历史运行的状态和日志；现有 `status` 的工具健康检查职责保留，执行记录查询的具体 CLI 形式在实现时确定。不自动断点续跑，也不接管遗留进程。后续若增加恢复执行，需校验命令及依赖是否变化，并明确中断任务的幂等性与重跑策略，不能直接跳过历史成功任务或重跑中断任务。
- **本地数据边界**：运行记录与日志属于生成数据，应将 `.devpulse\` 排除在版本控制之外；不保存环境变量或凭据快照。任务输出可能包含敏感信息，用户应在共享日志前审核。首期不自动清理历史记录；大量历史检索或跨进程事务需求出现后再考虑 SQLite。

### 三、开发落地路线图

首版范围包含 `init`、`pulse` 的并发调度、`gather` 和 `status`，不将并发或 AI 协同流推迟到后续版本。以下阶段为内部开发顺序，而不是对外版本的功能拆分。

#### 阶段一：建立脚手架与命令行骨架（MVP）

- 按 Copilot 仓库指令建立构建与命令行骨架。
- 确保打包后的可执行 JAR 能通过 `--help` 打印出规范的帮助说明。

#### 阶段二：攻克 gather 模块（打通 AI 协同流）

- 实现可复用的 Bash 探测与调用组件。
- 实现工具查找、项目指纹检测和中英文提示词生成。
- 实现 AWT 剪贴板写入及无图形环境降级，支持仅输出提示词。

#### 阶段三：攻克 pulse 模块（打通调度流）

- 引入 Typesafe Config 解析 HOCON 配置文件，完成默认值合并与执行前校验。
- 手写基于 Kahn 的拓扑排序函数，确保能够处理复杂的相互依赖。
- 实现依赖就绪任务的并发调度，落实默认 4 个任务的并发上限，以及 `parallelism`、`--jobs` 的覆盖规则。
- 完善 `ProcessBuilder` 的异常处理，落实停止新任务、等待运行中任务结束的失败策略，不会引起系统崩溃。
- 实现基于文件的运行状态与日志持久化、异常退出识别及历史查询，不包含自动断点续跑。
- 完成 `init` 默认配置生成与 `status` 本地工具健康状态展示，形成首版完整命令集合。

## English

This project aims to build a lightweight, efficient local DevOps orchestration toolbox with AI collaboration.

The technology stack, language conventions, build and packaging rules, resource management, and testing constraints are maintained in the [Copilot repository instructions](.github/copilot-instructions.md). This document covers functional behavior, architecture, and the development roadmap.

### 1. Configuration format and loading

- Support only HOCON in `.devpulse.conf`, not YAML.
- Explicitly parse the project configuration, merge built-in `reference.conf` defaults with `withFallback`, and call `resolve` for substitutions. Do not automatically load unrelated JVM system properties.
- Construct configuration models using the typed Typesafe Config API.
- Allow only local file or classpath includes, not remote URLs. Enforce this in resource loading rather than relying on user conventions.
- Support relative files and explicit `file` or `classpath` includes. Ignore absent optional includes and report absent `required` includes. Reject URLs and UNC network paths, and detect include cycles.
- Report configuration origins and line numbers when available. Validate field types, duplicate task names, unknown dependencies, and cycles before executing any command.
- Reject unknown tool or pipeline fields to avoid silently ignoring typos. Root-level helper fields may be defined for HOCON substitutions.
- Use HOCON examples consistently in generated gather prompts and init configurations.

### 2. Architecture

The system contains a CLI entry point, an environment inventory module, a reusable Bash component, a scheduling engine, and a configuration initializer with separate responsibilities:

```text
CLI input -> devpulse entry point -> corresponding module
```

#### 1. CLI entry point (`DevPulseCLI.java`)

**Responsibility**: Accept command-line arguments such as `gather` and `pulse`, and perform basic global validation.

#### 2. Environment inventory (`GatherCommand.java`)

**Responsibility**: Collect local environment facts, generate an AI prompt in the selected language (Chinese/English by default), print it, and copy it to the clipboard by default.

**Implementation steps**:

- **Environment and tool inventory**: Record OS, architecture, Java version, and Bash location. Check a fixed tool list using Bash `command -v`; do not execute version commands or health checks.
- **Project detection**: Check only the presence of root-level markers such as `package.json`, `pom.xml`, `Dockerfile`, and `.git`. Do not read file contents or recursively scan directories.
- **Collection boundaries**: Use environment variables only for Bash discovery and tool lookup; do not include their values or credentials in the prompt. Prompts contain project and tool locations, which users must review before sharing with external AI services.
- **Prompt output**: Generate Markdown containing facts, a HOCON example, Bash constraints, and command-review reminders. Support Chinese, English, or both. Bilingual mode retains same-page language navigation; single-language modes include only the corresponding language section without bilingual navigation. Keep local fact data such as paths and tool names, and the HOCON example, unchanged. Require AI to state assumptions instead of inventing tasks when facts are insufficient.
- **Encoding warning**: Before printing the prompt, check Java's `Console.charset()`, or `System.out.charset()` when no console is available. If the detected encoding is not UTF-8, print an English warning to standard error with the detected encoding and Git Bash's `Options > Text > Character set > UTF-8` setting. Do not change the language, output encoding, clipboard content, or successful exit code. This check reflects Java's detected encoding and cannot reliably read the Git Bash window's actual display character set. It may also warn when output is redirected and does not establish that garbling has occurred.
- **Clipboard**: Copy through AWT by default. In headless environments or when the clipboard is busy or inaccessible, warn and retain standard output; inventory still succeeds.
- **Options**: `gather --directory <directory>` selects the project, `--bash <path>` selects Bash, and `--no-clipboard` disables clipboard writes. `--language zh|en|both` controls the prompt language for both terminal and clipboard output, defaults to `both`, and rejects unsupported values before inventory. Invalid directories, missing Bash, and scan failures return nonzero exit codes. Use `gather --language en` to avoid Chinese prompt text when the terminal displays it incorrectly; this option does not fix terminal encoding or translate local fact data.

#### 3. Bash component (`BashShell.java`)

- **Unified interpreter**: Use Bash on every OS and Git Bash on Windows. Report missing Bash explicitly; never fall back to `sh`, CMD, PowerShell, or WSL.
- **Discovery order**: An explicit `--bash` path takes priority, followed by PATH lookup. Also check Git installation locations on Windows and accept only Bash executables with Git installation markers to avoid WSL launchers.
- **Verification**: Verify that Bash actually starts using a short-lived subprocess, rather than checking file presence alone.
- **Invocation**: Use `ProcessBuilder` to run `bash --noprofile --norc -s --`, write the complete UTF-8 script to standard input, and close input. Do not split commands on spaces. This avoids Windows command-line quoting altering quotes and variable expressions; this mode does not provide interactive input.
- **Environment and directory**: Set the working directory through `ProcessBuilder.directory()`. Inherit the parent environment, but remove `BASH_ENV`, `ENV`, and `BASH_FUNC_*` to avoid environment-selected startup files or imported user functions. Do not rely on aliases or user startup scripts.
- **Probe resource control**: Apply timeouts to verification and inventory, continuously read inventory output, and clean up probe processes.
- **Platform boundaries**: Shared Bash syntax does not make tools, paths, permissions, or OS capabilities identical. Git Bash is not a complete Linux environment.

#### 4. Scheduling engine (`PulseCommand.java`)

**Responsibility**: Read `.devpulse.conf`, determine dependency order, and execute local subprocesses.

**Implementation steps**:

- **Configuration**: Load HOCON according to the configuration rules and construct configuration models.
- **Target selection**: `pulse` defaults to `dev`; `pulse <name>` accepts either a pipeline or a single task. Reject targets that name both a task and a pipeline and require the collision to be resolved.
- **DAG construction**: Treat each entry in `tools` as a vertex and each `needs` relationship as a directed edge.
- **Dependency expansion**: Pipeline `tasks` declare final targets. Include all transitive dependencies automatically and run shared dependencies or duplicate targets once per invocation. List order does not define dependency order.
- **Ordering and cycle detection**: Validate the dependency graph using Kahn's algorithm or DFS. Report cycles before starting commands. Only enqueue tasks whose dependencies have all succeeded, and schedule within the concurrency limit.
- **Process execution**: Reuse the Bash component with the project configuration directory as the working directory. Merge standard error and output using `redirectErrorStream(true)`, continuously read UTF-8 output, and prefix log lines with task names. Tools must be installed and discoverable by Bash. Commands run with the current user's permissions; no sandbox is provided.
- **Concurrency**: Run at most 4 tasks by default. Support `parallelism` and `pulse --jobs`, with CLI options overriding project configuration and defaults. Validate positive integers before execution. This does not limit concurrency inside external tasks.
- **Failure policy**: Use the Bash exit code: zero means success, nonzero means failure. Do not implicitly enable `errexit` or `pipefail`; commands may set them explicitly. Once failure is observed, stop launching new tasks, wait for started tasks to finish, return a nonzero exit code, and summarize succeeded, failed, and skipped counts.
- **Options**: `--directory` selects the project, `--bash` selects Bash, and `--jobs` overrides concurrency. `--dry-run` validates the whole configuration and prints the expanded plan and commands without discovering Bash or starting tasks.
- **Execution boundaries**: Support foreground, non-interactive batch tasks only. Background-service lifecycle management and task timeouts are not provided. On cancellation, attempt to terminate known processes and visible descendants; detached background processes are not guaranteed to be terminated.

#### 5. Configuration initializer (`InitCommand.java`)

**Responsibility**: Generate an editable `.devpulse.conf` skeleton in the project directory. Do not scan the environment, call AI, execute tasks, or require an installed Bash.

- **Defaults**: UTF-8 HOCON containing `version = 1`, `parallelism = 4`, two example tools using only Bash `echo`, and a `dev` pipeline with a dependency relationship.
- **Directory selection**: Use the current working directory by default. `init --directory <directory>` selects another existing project directory; do not create directories automatically.
- **Overwrite protection**: Create new files only by default. Refuse existing configurations with a nonzero exit code. Allow overwriting regular files only with explicit `--force`; do not follow configuration-file symbolic links.
- **Feedback**: Print the configuration path and a command-review reminder on success. Clearly report invalid directories or write failures without executing example tasks.

#### 6. Local task execution persistence (planned)

**Approach**: Initially persist files within the project without introducing a database. Execution state currently exists only in memory, and logs go only to the console; the following behavior is planned.

```text
.devpulse\
  runs\
    <run-id>\
      state.conf
      logs\
        <task-id>.log
```

- **Storage layout**: Generate a unique `run-id` for each actual execution and create a separate run directory under `.devpulse\runs` in the project configuration directory, preventing repeated or concurrent runs from overwriting each other. Use generated `task-id` values for log filenames rather than using task names as paths. `--dry-run` creates no run records.
- **State format**: Use UTF-8 HOCON for `state.conf`, reusing Typesafe Config for reading and writing. Record the format version, run ID, target, project directory, effective concurrency limit, overall state and start/end times, plus each task's ID, name, dependencies, state, start/end times, exit code, failure reason, and relative log path. Omit fields such as end time and exit code until available.
- **State semantics**: Distinguish task states `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `SKIPPED`, `CANCELLED`, and `INTERRUPTED`. Ordinary failures are `FAILED`; tasks not started due to the failure policy are `SKIPPED`; tasks terminated by explicit cancellation are `CANCELLED`; tasks whose results cannot be confirmed after an unexpected executor exit are `INTERRUPTED`. Also persist the overall run state; unfinished records must not imply success.
- **Write timing and consistency**: Save the initial plan before execution, persist `RUNNING` before starting a task, save its result after completion, and write the overall result when the run ends. Attempt to save cancellation state on explicit cancellation. Serialize state writes within each run, writing a temporary file in the same directory before atomically replacing `state.conf`. Report filesystems without atomic replacement support instead of falling back to direct overwrite. Do not start tasks if initialization fails. Persistence failures during execution must be reported, stop new task launches, wait for started tasks to finish, and return a nonzero exit code.
- **Log persistence**: Continuously read merged standard output and error, retaining existing console output while writing each task's UTF-8 log file. Write logs independently of state snapshots rather than rewriting `state.conf` for every output line. Unexpected exits may lose an unflushed log tail.
- **Unexpected exit detection**: Record the executor PID and process start time when available, and hold an exclusive file lock in the run directory during execution; PID alone cannot establish process identity. When a later state read finds an unfinished run, check the file lock and executor identity before marking it interrupted, without modifying another live run. Once the original executor is confirmed to have exited, mark remaining `RUNNING` tasks as `INTERRUPTED`, unstarted tasks as `SKIPPED`, and persist the interrupted run state. If identity cannot be confirmed, retain the record and report uncertainty.
- **Query and recovery boundaries**: Initially support reading historical run states and logs. Retain the existing tool-health responsibility of `status`; determine the exact CLI form for execution-record queries during implementation. Do not automatically resume runs or take over orphaned processes. Future resume support must check for changed commands and dependencies and define idempotency and retry policies for interrupted tasks, rather than blindly skipping historically successful tasks or rerunning interrupted ones.
- **Local data boundaries**: Run records and logs are generated data; exclude `.devpulse\` from version control. Do not persist environment-variable or credential snapshots. Task output may contain sensitive information and must be reviewed before sharing. Do not automatically prune history initially; consider SQLite later if extensive historical queries or cross-process transactions become necessary.

### 3. Development roadmap

The first release includes `init`, concurrent `pulse`, `gather`, and `status`. Concurrency and AI collaboration are not deferred to later releases. These stages describe internal implementation order, not separate release scopes.

#### Stage 1: Project scaffold and CLI skeleton (MVP)

- Set up the build and CLI skeleton according to the Copilot repository instructions.
- Ensure the executable JAR prints standard help with `--help`.

#### Stage 2: Gather and AI collaboration

- Implement reusable Bash discovery and invocation.
- Implement tool lookup, project marker detection, and bilingual prompt generation.
- Implement AWT clipboard writes with headless fallback and a print-only option.

#### Stage 3: Pulse and scheduling

- Parse HOCON with Typesafe Config, merge defaults, and validate before execution.
- Implement Kahn-based dependency ordering for complex graphs.
- Implement dependency-ready concurrent scheduling with the default limit of 4 and `parallelism`/`--jobs` overrides.
- Handle `ProcessBuilder` failures by stopping new tasks and waiting for started tasks without crashing the application.
- Implement file-based run-state and log persistence, unexpected exit detection, and historical queries without automatic resume.
- Complete init configuration generation and status health reporting for the first release's full command set.
