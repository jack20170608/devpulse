# devpulse 核心 CLI 使用指南 / Core CLI guide

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

### 项目简介

devpulse 是一个面向开发者和运维工程师的本地 DevOps 工具箱，目标是用 `.devpulse.conf` 中的 HOCON 配置描述脚本、CLI 任务、依赖和流水线，无需搭建远程 CI/CD 平台。

**当前状态**：Pulse 独立工具实现 `init`、`pulse`、`review`、`status` 和 `stop`，支持普通任务和本地长驻进程。Gather 与 Jira 是独立工具；组合式 CLI 暂保留所有原有子命令。工具之间不相互依赖，也不内置 AI 调用；仅执行显式配置的长驻进程就绪检查，不自动探测 Docker 等工具的守护进程。

### 环境与构建

开发、构建和运行均需要 JDK 25 或更高版本，构建使用 Maven。`gather`、实际执行的 `pulse` 和 `status --tools` 还需要可用的 Bash；Windows 使用 Git Bash，不使用 CMD、PowerShell 或 WSL 替代 Bash。`init`、`review`、`pulse --dry-run`、历史列表和日志读取不需要 Bash。长驻进程的就绪检查及自定义停止命令使用启动时记录的 Bash 路径，不重新加载项目配置。

在仓库根目录构建并查看帮助：

```powershell
bash build.sh --clean
java -jar devpulse-cli\target\devpulse.jar --help
```

根目录 Maven 项目包含外部依赖版本模块 `devpulse-dependencies`、内部 BOM `devpulse-bom`、共享环境库 `devpulse-core`、独立工具聚合模块 `devpulse-plugins`（`pulse`、`gather`、`jira`）、组合兼容入口 `devpulse-cli` 和发行模块 `devpulse-dist`。Pulse、Gather、Jira 分别生成独立可执行 JAR；组合入口生成 `devpulse-cli\target\devpulse.jar`，不进入单工具发行包。以下示例使用组合入口保持原有命令用法；单独运行 Pulse 请使用 `java -jar devpulse-plugins\pulse\target\devpulse-pulse.jar [TARGET]`，相关命令通过 `pulse init|review|status|stop` 调用。安装说明见 [发行与安装](distribution.md)。Git Bash 中 `bash run.sh --help` 使用组合入口，`bash run-jira.sh --help` 只构建 Jira；脚本仅在其子进程中移除 `JAVA_TOOL_OPTIONS`。

### 命令概览与帮助

| 命令 | 用途 | 当前状态 |
|------|------|----------|
| `init` | 写入固定的配置模板，供你手动修改 | 已实现 |
| `gather` | 盘点本地环境并生成提示词，供你交给 AI 辅助编写配置 | 已实现 |
| `pulse` | 按依赖和并发上限执行任务或流水线 | 已实现 |
| `review` | 静态校验并审阅执行计划，不运行任务 | 已实现 |
| `status` | 查询运行历史、日志及长驻进程的存活与就绪状态，或检查工具可用性 | 已实现 |
| `stop` | 手动停止指定运行中的长驻进程 | 已实现 |
| 独立的 `devpulse-jira` | 从 HOCON/Markdown 模板创建 Jira Issue、导出与更新、流转、评论和附件；详见 [Jira CLI](plugins/jira.md) | 已实现 |

不指定子命令时显示主命令帮助。主命令和所有子命令均支持 `-h` / `--help` 和 `-V` / `--version`。

```powershell
java -jar devpulse-cli\target\devpulse.jar
java -jar devpulse-cli\target\devpulse.jar --version
java -jar devpulse-cli\target\devpulse.jar gather --help
```

### `init`：创建配置模板

**适用场景**：你知道项目的检查、构建等命令，希望先生成配置骨架，再自己填写。

`init` 在指定目录创建 UTF-8 编码的 `.devpulse.conf`，写入固定的 HOCON 模板。它不扫描项目、不调用 AI、不安装工具，也不执行任务。

模板包含 `version = 1`、`parallelism = 4`、`check` 和 `build` 两个任务，以及一个 `dev` 流水线。`build` 通过 `needs = [check]` 声明对 `check` 的依赖。**任务命令只是打印消息的 `echo` 占位符，不会进行实际检查或构建，需要替换为项目的真实命令。**

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 指定配置写入目录，默认为当前目录；目录必须已经存在 |
| `--force` | 用默认模板覆盖已有的普通配置文件；不会覆盖目录或符号链接 |

```powershell
# 在当前目录创建配置模板
java -jar devpulse-cli\target\devpulse.jar init

# 在已有项目目录创建配置模板
java -jar devpulse-cli\target\devpulse.jar init --directory "D:\your-project"

# 覆盖已有配置，原内容将丢失
java -jar devpulse-cli\target\devpulse.jar init --directory "D:\your-project" --force
```

成功时输出配置文件路径并返回退出码 `0`。目录不存在、配置已存在且未指定 `--force`，或文件无法写入时，输出错误并返回 `1`。

### `gather`：生成辅助编写配置的 AI 提示词

**适用场景**：你希望 AI 根据本机可用工具和项目类型，辅助编写 `.devpulse.conf`，而不是从空白开始。

`gather` 收集以下事实，按所选语言组装 Markdown 提示词并输出到终端，默认使用中英文对照并复制到剪贴板：

- 项目的绝对路径、操作系统、架构、当前 Java 版本和 Bash 路径。
- 固定工具清单的查找结果：`git`、`docker`、`kubectl`、`helm`、`node`、`npm`、`pnpm`、`yarn`、`java`、`mvn`、`gradle`、`python`、`python3`、`go`、`cargo`、`make`、`terraform`。通过 Bash 的 `command -v` 记录位置，未找到的工具标为缺失。
- 项目根目录中的标志是否存在，例如 `.git`、`package.json`、`pom.xml`、Gradle 构建文件、`Dockerfile`、Compose 配置、Python 依赖文件、`go.mod`、`Cargo.toml`、`Makefile` 和 `.devpulse.conf`。

提示词包含 HOCON 示例、任务依赖要求、Bash 执行约束和命令审核提醒，要求 AI 不捏造项目脚本、信息不足时说明假设，并避免默认推荐安装、删除、部署或推送等操作。

**能力边界**：不读取项目文件内容、不递归扫描、不采集环境变量值，也不执行工具版本或健康检查。找到工具不代表工具配置正确或服务可用。`gather` 不会自动调用 AI、生成或写入 `.devpulse.conf`，也不会执行项目任务。

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 指定盘点的项目目录，默认为当前目录；目录必须已经存在 |
| `--bash <路径>` | 显式指定 Bash 可执行文件；Windows 应指定 Git Bash |
| `--language zh\|en\|both` | 选择终端及剪贴板中的提示词语言，默认 `both` |
| `--no-clipboard` | 仅输出提示词，不修改剪贴板 |

```powershell
# 盘点当前目录，输出提示词并尝试复制到剪贴板
java -jar devpulse-cli\target\devpulse.jar gather

# 盘点指定项目，不修改剪贴板
java -jar devpulse-cli\target\devpulse.jar gather --directory "D:\your-project" --no-clipboard

# 显式指定 Git Bash
java -jar devpulse-cli\target\devpulse.jar gather --bash "C:\Program Files\Git\bin\bash.exe"

# 中文显示乱码时，仅输出英文提示词
java -jar devpulse-cli\target\devpulse.jar gather --language en --no-clipboard
```

成功时返回 `0`。剪贴板不可用时会输出警告，提示词仍保留在终端，不影响盘点成功。目录无效、找不到可用 Bash、扫描失败或被中断时返回 `1`。

提示词包含本地项目路径和工具位置，发送给外部 AI 前应先审核。由于未读取项目文件，通常还需补充实际脚本和命令；AI 生成的配置也必须人工审核，不能直接视为可靠的执行结果。Java 检测到非 UTF-8 控制台或标准输出编码时会给出警告；该检测不能确认终端实际显示编码，`--language en` 也不会修复终端编码。

### `pulse`：执行任务或流水线

`pulse [TARGET]` 从项目目录读取 `.devpulse.conf`，默认执行名为 `dev` 的流水线或任务。自动补齐传递依赖，共享依赖和重复目标只执行一次；普通依赖必须成功退出，长驻进程依赖必须通过就绪检查，下游才会启动。独立任务可在并发上限内同时执行。

执行前校验完整配置，包括字段类型、未知字段、重复任务名、未知依赖和循环依赖。目标同时是任务名和流水线名时拒绝执行。配置错误不会启动任务。

| 参数 | 作用 |
|------|------|
| `TARGET` | 任务或流水线名称，默认 `dev` |
| `--directory <目录>` | 项目配置目录，默认当前目录 |
| `--bash <路径>` | 显式指定 Bash；Windows 使用 Git Bash |
| `--jobs <N>` | 正整数并发上限，覆盖配置中的 `parallelism`，内置默认值为 `4` |
| `--dry-run` | 仅校验并打印计划和命令，不探测 Bash、不执行任务、不创建运行记录 |

```powershell
java -jar devpulse-cli\target\devpulse.jar pulse
java -jar devpulse-cli\target\devpulse.jar pulse build --jobs 2
java -jar devpulse-cli\target\devpulse.jar pulse dev --directory "D:\your-project" --dry-run
```

任务通过非登录、非交互 Bash 执行，工作目录为项目配置目录。普通任务的输出和错误流合并，控制台日志带任务名前缀，并保存 UTF-8 日志；长驻进程的合并输出直接写入日志文件，不在控制台实时转发，以便 CLI 返回后仍能输出。普通任务以 Bash 退出码判断结果；脚本提前 `exit 0` 不会因输入管道关闭而被误判为失败。不隐式启用 `errexit` 或 `pipefail`，需要时由命令显式设置。

发现失败后不再启动新任务，等待已启动任务结束，并汇总成功、失败和跳过数量。全部成功返回 `0`；配置、任务或持久化失败返回 `1`；执行被中断时返回 `130`。取消时尽力清理已知任务进程及其可见后代。

支持非交互批任务和显式声明的本地长驻进程，不提供普通批任务超时或沙箱。运行前必须审核命令；并发上限限制批任务和长驻进程的启动等待阶段，不限制已就绪的长驻进程数量、探测进程或任务内部的并发。

### 本地长驻进程

在任务中声明 `type = daemon`，将依赖满足条件从“成功退出”改为“通过就绪检查”。省略 `type` 或使用 `type = task` 保持原有批任务行为；批任务不能配置长驻进程字段。

以下配置以项目已有的 Node 测试服务器为例，命令和健康接口必须按实际项目调整；不会自动安装 Node 或创建服务器代码：

```hocon
version = 1
parallelism = 1
tools = [
  {
    name = server
    type = daemon
    cmd = "node server.js"
    ready {
      cmd = "curl --fail --silent --max-time 2 http://127.0.0.1:3000/health"
      interval = 500ms
      timeout = 30s
      checkTimeout = 2s
    }
    shutdownHook = false
    stopTimeout = 5s
  },
  {
    name = check
    cmd = "node check.js"
    needs = [server]
  }
]
pipelines { dev { tasks = [check] } }
```

| 字段 | 含义 |
|------|------|
| `ready.cmd` | 必填；退出码 `0` 表示就绪，命令必须是快速、只读检查 |
| `ready.interval` | 检查间隔，默认 `500ms` |
| `ready.timeout` | 初次启动等待总时限，默认 `30s` |
| `ready.checkTimeout` | 每次检查的时限，默认 `2s`；启动阶段受剩余总时限约束 |
| `shutdownHook` | 默认 `false`：CLI 返回后保留进程；`true`：运行结束、失败或可处理的退出时自动清理 |
| `stop` | 可选 Bash 停止命令；未配置时终止已确认身份的进程及可见后代 |
| `stopTimeout` | 停止命令和退出等待的宽限时间，默认 `5s`；随后尽力强制终止并再确认最多 `2s` |

检查或停止命令超时后，回收该命令进程及可见后代可额外等待最多 `2s`；这段回收时间不包含在配置的命令时限内。

长驻进程命令必须保持前台运行，不使用 `&`、`nohup` 或自行 fork/脱离进程树。达到就绪后释放普通执行槽，因此 `--jobs 1` 也能运行依赖服务器的检查。执行期间持续检查存活和就绪状态：意外退出（包括退出码 `0`）、检查失败或超时使本次运行失败，停止启动新任务，等待已经启动的批任务结束。不自动重启，也不接管已有实例或复用被占用的端口。

开启 hook 的进程按计划的逆依赖顺序清理；未开启 hook 的进程即使启动检查失败或下游失败，也保留供手动清理。初始化元数据失败时，会尽力终止尚未可靠记录的进程，避免产生无法管理的实例。强制杀死 CLI、断电或已脱离进程树的进程不保证自动清理。

检查和停止命令可使用 `DEVPULSE_DAEMON_PID`、`DEVPULSE_RUN_ID` 和 `DEVPULSE_TASK_NAME`。停止命令应只操作对应实例；不要按名称批量终止进程，也不要自动删除数据库数据目录或 volume。停止命令失败或超时后仍尽力回收已记录进程，返回失败并保留诊断。

### `stop`：手动清理长驻进程

```powershell
# 停止最新运行的所有长驻进程
java -jar devpulse-cli\target\devpulse.jar stop

# 停止指定运行中的单个长驻进程
java -jar devpulse-cli\target\devpulse.jar stop server --run "<run-id>"

# 停止其他项目的指定运行
java -jar devpulse-cli\target\devpulse.jar stop --directory "D:\your-project" --run "<run-id>"
```

`stop [TASK]` 支持 `--directory` 和 `--run`，默认选择最新运行；省略任务名时按逆依赖顺序清理该运行的所有已记录长驻进程。它使用保存的停止配置，不依赖当前 `.devpulse.conf`；不会清理其他历史运行，也不会删除日志或业务数据。进程身份通过 PID 和启动时间核验，PID 已复用时不终止新进程，身份无法确认时拒绝清理。

已退出实例重复清理返回 `0`；不存在任何运行或所选运行没有长驻进程也返回 `0`。指定任务未记录进程、停止命令失败或无法确认清理结果返回 `1`；中断返回 `130`。手动停止仍被执行中的 DAG 使用的长驻进程，会触发该运行的故障策略。

### `review`：执行前审阅计划

`review [TARGET]` 复用 `pulse` 的完整配置校验和依赖补齐规则，默认目标为 `dev`，但不探测 Bash、不执行任务、不创建运行记录。

| 参数 | 作用 |
|------|------|
| `TARGET` | 任务或流水线名称，默认 `dev` |
| `--directory <目录>` | 项目配置目录，默认当前目录 |
| `--jobs <N>` | 覆盖审阅时显示的并发上限，不修改配置 |
| `--plain` | 强制输出纯文本计划，不打开 TUI |

```powershell
java -jar devpulse-cli\target\devpulse.jar review
java -jar devpulse-cli\target\devpulse.jar review build --plain
```

交互终端中显示分层 DAG、依赖深度、前置和后继连线及完整命令，长驻进程还显示就绪命令、超时、hook 和停止配置；`Tab` 可切换 DAG 和任务列表。上／下方向键选择任务，`Home`／`End` 选择首／末任务，左／右方向键水平平移图，`w`／`s` 垂直平移，`r` 定位到选中任务，`PageUp`／`PageDown` 滚动详情，`q` 退出。窗口小于 `60 × 16` 时提示放大，退出时恢复终端。输出重定向或无可用交互终端时自动使用纯文本计划。`review --plain` 与 `pulse --dry-run` 也显示所有生命周期命令，但不执行检查或清理。

成功返回 `0`，配置、目标或界面错误返回 `1`，中断返回 `130`。拓扑顺序和依赖深度不是固定执行批次；静态校验通过不代表命令安全或工具可用。

### `status`：查询运行状态和日志

每次实际执行 `pulse` 都生成独立运行 ID，在项目的 `.devpulse\runs\<run-id>` 下保存 `state.conf`、运行锁和任务日志。`status` 默认查看开始时间最新的运行，显示整体及各任务的状态、退出码、错误、UTC 起止时间和耗时。

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 项目目录，默认当前目录；记录读取不要求配置文件，长驻进程就绪检查使用已记录的 Bash |
| `--run <ID>` | 查看指定历史运行，默认选择最新运行 |
| `--list` | 按开始时间从新到旧列出全部运行 |
| `--log <任务名>` | 输出选中运行的任务日志，可与 `--run` 配合 |
| `--tools` | 仅检查 Bash 和固定工具清单的可用性，不查询执行历史 |
| `--bash <路径>` | 仅与 `--tools` 配合，显式指定 Bash |

```powershell
java -jar devpulse-cli\target\devpulse.jar status
java -jar devpulse-cli\target\devpulse.jar status --list
java -jar devpulse-cli\target\devpulse.jar status --run "<run-id>" --log build
java -jar devpulse-cli\target\devpulse.jar status --tools
```

`--list` 不能与 `--run` 或 `--log` 混用；`--tools` 不能与历史查询选项混用。查询成功返回 `0`，即使历史任务失败；没有运行记录时也返回 `0`。读取或恢复失败返回 `1`，参数冲突返回 `2`，工具盘点中断返回 `130`。

任务历史状态区分 `PENDING`、`RUNNING`、`READY`、`SUCCESS`、`FAILED`、`SKIPPED`、`CANCELLED` 和 `INTERRUPTED`。`READY` 表示长驻进程在执行时达到就绪，不代表后来始终存活。读取遗留的未完成记录时，通过运行锁及执行器身份判断是否已退出；确认退出后保存中断状态，无法确认时保留记录并警告，不自动续跑或接管进程。

详情查询单独显示长驻进程的实时状态、PID 和 hook：`STARTING`、`READY`、`NOT_READY`、`EXITED`、`STOPPED` 或 `UNKNOWN`。仅在进程身份匹配时执行保存的就绪检查，Bash 不可用或身份无法确认时显示 `UNKNOWN`。整体运行状态仍表示该次流水线的结果，不因 CLI 返回后的变化追溯改写。`--list` 和 `--log` 只读取历史或日志，不执行就绪检查。

时间以 UTC 显示到秒，耗时自动使用 `ms`、`s`、`m` 或 `h`；运行中标注 `(running)`，取消或中断的估计耗时以 `~` 标记。未启动或无法计算的时间显示 `-`。

`--tools` 仅检查工具能否被 Bash 找到，不执行版本或守护进程健康检查；找到 `docker` 不代表 Docker 服务正在运行。历史和日志不自动清理，`.devpulse\` 应排除在版本控制之外；任务输出可能包含敏感信息，共享前必须审核。

### 配置与执行工作流

自己编写配置时，运行 `init`，然后修改模板中的任务命令。需要 AI 辅助时，按以下流程操作；这条路径不要求先运行 `init`：

1. 运行 `gather`，获取环境提示词。
2. 审核提示词后，将其交给你选择的本地或云端 AI，并补充实际项目脚本和需求。
3. 审核 AI 生成的 HOCON 配置，手动保存为项目目录下的 `.devpulse.conf`。
4. 运行 `review` 或 `pulse --dry-run`，检查依赖和完整命令。
5. 确认后运行 `pulse`，再用 `status` 查询结果或日志。

devpulse 不内置 AI 调用；是否向外部服务分享提示词由你决定。自己编写的配置也应先审阅，再执行。

### 配置示例（`.devpulse.conf`）

配置采用 HOCON，不支持 YAML。以下示例与 `init` 模板一致，可由 `pulse` 执行，但仅打印占位消息，并非已经发现的项目任务：

```hocon
version = 1
parallelism = 4

tools = [
  {
    name = check
    cmd = "echo 'devpulse: replace with a project check'"
  },
  {
    name = build
    cmd = "echo 'devpulse: replace with a project build'"
    needs = [check]
  }
]

pipelines {
  dev {
    description = "示例检查与构建 / Example check and build"
    tasks = [check, build]
  }
}
```

`tools` 描述任务名称和命令，`needs` 描述依赖，`pipelines` 将任务组成流水线，`parallelism` 描述并发上限。配置命令的执行约定是非登录、非交互 Bash；Windows 使用 Git Bash，不依赖用户的 alias 或启动文件。实际执行前必须审核命令，devpulse 不是沙箱。

配置支持本地文件及 classpath include 和 HOCON 变量引用，拒绝 URL／UNC 网络 include、包含循环、未知工具或流水线字段及无效依赖。缺失的可选 include 被忽略，`required` 缺失时报错；根级辅助字段可用于变量引用。

### 实现说明

架构与执行边界见 [架构与实现设计](architecture.md)，技术栈及仓库约束见 [Copilot 仓库指令](../.github/copilot-instructions.md)。

## English

[Project home](../readme.MD)

### Overview

devpulse is a local DevOps toolbox for developers and operations engineers. It aims to describe scripts, CLI tasks, dependencies, and pipelines using HOCON in `.devpulse.conf`, without requiring a remote CI/CD platform.

**Current status**: The independent Pulse tool implements `init`, `pulse`, `review`, `status`, and `stop`, supporting batch tasks and local long-running processes. Gather and Jira are independent tools; the combined compatibility CLI retains all former subcommands. The tools do not depend on one another and have no built-in AI calls. Pulse runs explicitly configured daemon readiness checks, not automatic health checks for tool daemons such as Docker.

### Requirements and build

Development, builds, and runtime require JDK 25 or later. Builds use Maven. `gather`, actual `pulse` execution, and `status --tools` also require working Bash; on Windows, use Git Bash rather than CMD, PowerShell, or WSL as a Bash replacement. `init`, `review`, `pulse --dry-run`, history lists, and log reads do not require Bash. Daemon readiness checks and custom stop commands use the Bash path recorded at startup without reloading project configuration.

Build and view help from the repository root:

```powershell
bash build.sh --clean
java -jar devpulse-cli\target\devpulse.jar --help
```

The root Maven reactor contains `devpulse-dependencies` for external versions, `devpulse-bom` for internal versions, the shared environment library `devpulse-core`, independent tools in `devpulse-plugins` (`pulse`, `gather`, and `jira`), the combined compatibility entry point `devpulse-cli`, and `devpulse-dist` for selective archives. Pulse, Gather, and Jira produce separate executable JARs; the combined entry point produces `devpulse-cli\target\devpulse.jar` and is not included in individual tool archives. The examples below use that entry point to preserve existing command syntax. To run Pulse alone, use `java -jar devpulse-plugins\pulse\target\devpulse-pulse.jar [TARGET]`; its related commands are `pulse init|review|status|stop`. See [distribution and installation](distribution.md). In Git Bash, `bash run.sh --help` launches the combined entry point, while `bash run-jira.sh --help` builds only Jira. Scripts remove `JAVA_TOOL_OPTIONS` only in their child processes.

### Command overview and help

| Command | Purpose | Current status |
|---------|---------|----------------|
| `init` | Write a fixed configuration template for manual editing | Implemented |
| `gather` | Inventory the local environment and generate a prompt for you to share with AI for configuration assistance | Implemented |
| `pulse` | Run tasks or pipelines with dependencies and a concurrency limit | Implemented |
| `review` | Validate and review execution plans without running tasks | Implemented |
| `status` | Query history, logs, daemon liveness/readiness, or tool availability | Implemented |
| `stop` | Manually stop daemons in a selected run | Implemented |
| Standalone `devpulse-jira` | Create Jira issues from HOCON/Markdown templates, export and update, transition, comment, and attach files; see [Jira CLI](plugins/jira.md) | Implemented |

Running without a subcommand displays the main command help. The main command and all subcommands support `-h` / `--help` and `-V` / `--version`.

```powershell
java -jar devpulse-cli\target\devpulse.jar
java -jar devpulse-cli\target\devpulse.jar --version
java -jar devpulse-cli\target\devpulse.jar gather --help
```

### `init`: create a configuration template

**When to use it**: You know your project's check, build, or other commands and want a configuration skeleton to fill in manually.

`init` creates a UTF-8 `.devpulse.conf` in the selected directory with a fixed HOCON template. It does not scan the project, call AI, install tools, or execute tasks.

The template contains `version = 1`, `parallelism = 4`, two tasks named `check` and `build`, and a `dev` pipeline. `build` declares its dependency on `check` using `needs = [check]`. **The task commands are only message-printing `echo` placeholders, not real checks or builds; replace them with actual project commands.**

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Select the configuration destination; defaults to the current directory, which must already exist |
| `--force` | Replace an existing regular configuration file with the default template; directories and symbolic links are not overwritten |

```powershell
# Create a configuration template in the current directory
java -jar devpulse-cli\target\devpulse.jar init

# Create a configuration template in an existing project directory
java -jar devpulse-cli\target\devpulse.jar init --directory "D:\your-project"

# Overwrite an existing configuration, losing its original contents
java -jar devpulse-cli\target\devpulse.jar init --directory "D:\your-project" --force
```

On success, the command prints the configuration path and returns exit code `0`. A missing directory, an existing configuration without `--force`, or a write failure produces an error and returns `1`.

### `gather`: generate an AI prompt for configuration assistance

**When to use it**: You want AI to help write `.devpulse.conf` based on locally available tools and project markers rather than starting from scratch.

`gather` collects the following facts, formats them as a Markdown prompt in the selected language, and prints it to the terminal. By default, the prompt is bilingual Chinese/English and is also copied to the clipboard:

- The absolute project path, operating system, architecture, current Java version, and Bash path.
- Lookup results for a fixed tool list: `git`, `docker`, `kubectl`, `helm`, `node`, `npm`, `pnpm`, `yarn`, `java`, `mvn`, `gradle`, `python`, `python3`, `go`, `cargo`, `make`, and `terraform`. Bash's `command -v` records their locations; tools that cannot be found are marked as missing.
- Whether project markers exist in the project root, such as `.git`, `package.json`, `pom.xml`, Gradle build files, `Dockerfile`, Compose configurations, Python dependency files, `go.mod`, `Cargo.toml`, `Makefile`, and `.devpulse.conf`.

The prompt includes a HOCON example, task dependency requirements, Bash execution constraints, and command review reminders. It asks AI not to invent project scripts, to state assumptions when information is insufficient, and to avoid recommending installation, deletion, deployment, or pushing by default.

**Limitations**: It does not read project file contents, scan recursively, collect environment variable values, or run tool version or health checks. Finding a tool does not establish correct configuration or service availability. `gather` does not automatically call AI, generate or write `.devpulse.conf`, or execute project tasks.

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Select the project to inspect; defaults to the current directory, which must already exist |
| `--bash <path>` | Explicitly select the Bash executable; on Windows, select Git Bash |
| `--language zh\|en\|both` | Select the terminal and clipboard prompt language; defaults to `both` |
| `--no-clipboard` | Print the prompt without changing the clipboard |

```powershell
# Inspect the current directory, print the prompt, and try to copy it to the clipboard
java -jar devpulse-cli\target\devpulse.jar gather

# Inspect a selected project without changing the clipboard
java -jar devpulse-cli\target\devpulse.jar gather --directory "D:\your-project" --no-clipboard

# Explicitly select Git Bash
java -jar devpulse-cli\target\devpulse.jar gather --bash "C:\Program Files\Git\bin\bash.exe"

# Print only the English prompt if Chinese text appears garbled
java -jar devpulse-cli\target\devpulse.jar gather --language en --no-clipboard
```

Success returns `0`. If the clipboard is unavailable, the command prints a warning and leaves the prompt in the terminal; the inventory still succeeds. An invalid directory, missing working Bash, failed scan, or interruption returns `1`.

The prompt contains local project paths and tool locations; review it before sharing with external AI. Since project files are not read, you will usually need to supply actual scripts and commands as additional context. AI-generated configuration also requires human review and must not be assumed reliable for execution. A warning is printed if Java detects non-UTF-8 console or standard output encoding; this cannot verify the terminal's actual display encoding, and `--language en` does not fix terminal encoding.

### `pulse`: run tasks or pipelines

`pulse [TARGET]` reads `.devpulse.conf` from the project directory and defaults to the pipeline or task named `dev`. It includes transitive dependencies automatically and runs shared dependencies and duplicate targets only once. Batch dependencies must exit successfully and daemon dependencies must pass readiness before downstream tasks start. Independent tasks can run concurrently within the limit.

The entire configuration is validated before execution, including field types, unknown fields, duplicate task names, unknown dependencies, and dependency cycles. A target that names both a task and a pipeline is rejected. Configuration errors do not start tasks.

| Option | Effect |
|--------|--------|
| `TARGET` | Task or pipeline name; defaults to `dev` |
| `--directory <directory>` | Project configuration directory; defaults to the current directory |
| `--bash <path>` | Explicitly select Bash; use Git Bash on Windows |
| `--jobs <N>` | Positive concurrency limit overriding configuration `parallelism`, whose built-in default is `4` |
| `--dry-run` | Validate and print the plan and commands without discovering Bash, running tasks, or creating run records |

```powershell
java -jar devpulse-cli\target\devpulse.jar pulse
java -jar devpulse-cli\target\devpulse.jar pulse build --jobs 2
java -jar devpulse-cli\target\devpulse.jar pulse dev --directory "D:\your-project" --dry-run
```

Tasks run through non-login, non-interactive Bash in the project configuration directory. Batch output and errors are merged, prefixed in the console, and saved as UTF-8 logs. Daemon output is merged and redirected directly to its log, without live console forwarding, so output continues after CLI exit. Bash's exit code determines batch results; an early `exit 0` is not misclassified as failure when the input pipe closes. `errexit` and `pipefail` are not enabled implicitly; set them explicitly when needed.

After observing a failure, no new tasks start. Already started tasks finish, and a summary reports successful, failed, and skipped counts. All tasks succeeding returns `0`; configuration, task, or persistence failures return `1`; execution interruption returns `130`. Cancellation makes a best-effort attempt to terminate known task processes and their visible descendants.

Non-interactive batch tasks and explicitly declared local daemons are supported. There is no ordinary batch-task timeout or sandbox. Review commands before execution. The concurrency limit covers batch tasks and daemon startup waits, not already-ready daemons, probe processes, or internal task concurrency.

### Local long-running processes

Declare `type = daemon` to satisfy dependencies through readiness instead of successful exit. Omitting `type` or using `type = task` preserves batch behavior; batch tasks cannot configure daemon fields.

This example assumes an existing Node test server. Adjust commands and the health endpoint to the project; it does not install Node or create server code:

```hocon
version = 1
parallelism = 1
tools = [
  {
    name = server
    type = daemon
    cmd = "node server.js"
    ready {
      cmd = "curl --fail --silent --max-time 2 http://127.0.0.1:3000/health"
      interval = 500ms
      timeout = 30s
      checkTimeout = 2s
    }
    shutdownHook = false
    stopTimeout = 5s
  },
  {
    name = check
    cmd = "node check.js"
    needs = [server]
  }
]
pipelines { dev { tasks = [check] } }
```

| Field | Meaning |
|-------|---------|
| `ready.cmd` | Required; exit code `0` means ready. Use a fast, read-only check |
| `ready.interval` | Check interval, default `500ms` |
| `ready.timeout` | Overall initial startup wait, default `30s` |
| `ready.checkTimeout` | Individual check timeout, default `2s`, bounded by remaining startup time |
| `shutdownHook` | Default `false` retains the process after CLI exit; `true` cleans up on completion, failure, or handled exit |
| `stop` | Optional Bash stop command; otherwise terminate identity-confirmed processes and visible descendants |
| `stopTimeout` | Stop-command and exit-wait grace period, default `5s`; followed by best-effort forced termination and up to `2s` confirmation |

After a check or stop command times out, reclaiming that command and its visible descendants may take up to an additional `2s`, outside the configured command timeout.

Daemon commands must remain foreground processes: do not use `&`, `nohup`, or fork/detach themselves. Readiness releases the normal execution slot, so `--jobs 1` can run a check depending on a server. While the pipeline executes, liveness and readiness are monitored. Unexpected exit, including code `0`, failed checks, or check timeouts fail the run and stop new tasks while already-started batch tasks finish. There is no automatic restart, existing-instance takeover, or reuse of occupied ports.

Hook-enabled daemons are cleaned up in reverse plan dependency order. Daemons without hooks remain available for manual cleanup even after startup checks or downstream tasks fail. If initial metadata cannot be reliably recorded, startup attempts to terminate the untracked process. Forced CLI termination, power loss, and detached descendants are not guaranteed to be cleaned up.

Checks and stop commands receive `DEVPULSE_DAEMON_PID`, `DEVPULSE_RUN_ID`, and `DEVPULSE_TASK_NAME`. Stop commands must target only the corresponding instance; do not kill processes by name or automatically delete database directories/volumes. Failed or timed-out stop commands still trigger best-effort process reclamation and return failure with diagnostics.

### `stop`: manually clean up daemons

```powershell
# Stop all daemons in the latest run
java -jar devpulse-cli\target\devpulse.jar stop

# Stop one daemon in a selected run
java -jar devpulse-cli\target\devpulse.jar stop server --run "<run-id>"

# Stop a selected run in another project
java -jar devpulse-cli\target\devpulse.jar stop --directory "D:\your-project" --run "<run-id>"
```

`stop [TASK]` accepts `--directory` and `--run`, defaulting to the latest run. Without a task name, it stops all recorded daemons in that run in reverse dependency order. It uses recorded stop settings, not the current `.devpulse.conf`, and does not clean other historical runs or delete logs/business data. PID and start time confirm ownership; reused PIDs are not terminated, and uncertain identities are refused.

Repeated cleanup of exited instances returns `0`, as do empty history or a selected run without daemons. A requested task without a recorded process, failed stop command, or unconfirmed cleanup returns `1`; interruption returns `130`. Manually stopping a daemon still used by an executing DAG triggers that run's failure policy.

### `review`: review plans before execution

`review [TARGET]` shares `pulse`'s full configuration validation and dependency completion rules, defaulting to `dev`, but does not discover Bash, execute tasks, or create run records.

| Option | Effect |
|--------|--------|
| `TARGET` | Task or pipeline name; defaults to `dev` |
| `--directory <directory>` | Project configuration directory; defaults to the current directory |
| `--jobs <N>` | Override the displayed concurrency limit without modifying configuration |
| `--plain` | Force a plain-text plan instead of opening the TUI |

```powershell
java -jar devpulse-cli\target\devpulse.jar review
java -jar devpulse-cli\target\devpulse.jar review build --plain
```

An interactive terminal shows a layered DAG, dependency depth, prerequisite/dependent connections, and complete commands. Daemons also show readiness commands, timeouts, hooks, and stop settings. `Tab` switches between graph and task list. Up/Down selects tasks, `Home`/`End` selects the first/last task, Left/Right pans horizontally, `w`/`s` pans vertically, `r` centers selection, `PageUp`/`PageDown` scrolls details, and `q` exits. Windows smaller than `60 × 16` show an enlargement notice, and exit restores the terminal. Redirected or unavailable interactive output uses a plain-text plan. `review --plain` and `pulse --dry-run` also show all lifecycle commands without running checks or cleanup.

Success returns `0`, configuration, target, or interface errors return `1`, and interruption returns `130`. Topological order and dependency depth are not fixed execution batches; valid configuration does not guarantee command safety or tool availability.

### `status`: query run status and logs

Every actual `pulse` execution creates an independent run ID, storing `state.conf`, a run lock, and task logs under the project's `.devpulse\runs\<run-id>`. By default, `status` shows the run with the latest start time, including overall and task states, exit codes, errors, UTC start/finish times, and durations.

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Project directory; defaults to current directory. Reading records needs no configuration; daemon readiness uses recorded Bash |
| `--run <ID>` | Select a historical run instead of the latest |
| `--list` | List all runs by start time, newest first |
| `--log <task>` | Print a task log from the selected run; can be combined with `--run` |
| `--tools` | Check only Bash and fixed-list tool availability, without querying run history |
| `--bash <path>` | Explicit Bash path, used only with `--tools` |

```powershell
java -jar devpulse-cli\target\devpulse.jar status
java -jar devpulse-cli\target\devpulse.jar status --list
java -jar devpulse-cli\target\devpulse.jar status --run "<run-id>" --log build
java -jar devpulse-cli\target\devpulse.jar status --tools
```

`--list` cannot be combined with `--run` or `--log`; `--tools` cannot be combined with history query options. Successful queries return `0`, even for failed historical tasks or an empty history. Reading or recovery failures return `1`, conflicting options return `2`, and tool inventory interruption returns `130`.

Historical task states distinguish `PENDING`, `RUNNING`, `READY`, `SUCCESS`, `FAILED`, `SKIPPED`, `CANCELLED`, and `INTERRUPTED`. `READY` records that a daemon became ready during execution, not that it remains alive later. Reading unfinished records checks the run lock and executor identity. Confirmed exit persists an interrupted result; uncertainty preserves the record and prints a warning. There is no automatic resume or process takeover.

Detailed queries separately show live daemon state, PID, and hook: `STARTING`, `READY`, `NOT_READY`, `EXITED`, `STOPPED`, or `UNKNOWN`. Recorded checks run only for matching process identities; unavailable Bash or unconfirmed identity yields `UNKNOWN`. The overall run state remains the pipeline outcome and is not retroactively changed after CLI exit. `--list` and `--log` read only history/logs without readiness probes.

Times are displayed in UTC to whole seconds, with durations using `ms`, `s`, `m`, or `h` automatically. Active runs show `(running)`; estimated durations for cancellation or interruption are marked `~`. Unstarted or unavailable times display `-`.

`--tools` only checks whether Bash can find tools; it does not execute version commands or daemon health checks. Finding `docker` does not establish that its daemon is running. History and logs are not automatically cleaned up; exclude `.devpulse\` from version control and review task output for sensitive information before sharing.

### Configuration and execution workflow

To write configuration yourself, run `init` and edit the template's task commands. For AI assistance, follow this workflow; it does not require running `init` first:

1. Run `gather` to obtain the environment prompt.
2. Review the prompt, share it with your chosen local or cloud AI, and supply actual project scripts and requirements.
3. Review the AI-generated HOCON configuration and manually save it as `.devpulse.conf` in the project directory.
4. Run `review` or `pulse --dry-run` to inspect dependencies and complete commands.
5. After approval, run `pulse`, then use `status` to query results or logs.

devpulse does not include AI calls; you decide whether to share the prompt with external services. Manually written configurations should also be reviewed before execution.

### Configuration example (`.devpulse.conf`)

Configuration uses HOCON, not YAML. The following example matches the `init` template and can be executed by `pulse`, but only prints placeholder messages rather than running discovered project tasks:

```hocon
version = 1
parallelism = 4

tools = [
  {
    name = check
    cmd = "echo 'devpulse: replace with a project check'"
  },
  {
    name = build
    cmd = "echo 'devpulse: replace with a project build'"
    needs = [check]
  }
]

pipelines {
  dev {
    description = "示例检查与构建 / Example check and build"
    tasks = [check, build]
  }
}
```

`tools` describes task names and commands, `needs` describes dependencies, `pipelines` groups tasks into pipelines, and `parallelism` describes the concurrency limit. The configuration's execution convention is non-login, non-interactive Bash; Windows uses Git Bash, without depending on user aliases or startup files. Review commands before actual execution; devpulse is not a sandbox.

Configuration supports local file and classpath includes and HOCON substitutions. It rejects URL/UNC network includes, include cycles, unknown tool or pipeline fields, and invalid dependencies. Missing optional includes are ignored; missing `required` includes fail. Root-level helper fields can be used for substitutions.

### Implementation notes

See the [Architecture and implementation design](architecture.md) for architecture and execution boundaries, and the [Copilot repository instructions](../.github/copilot-instructions.md) for the technology stack and repository constraints.
