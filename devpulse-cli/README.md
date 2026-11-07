# 组合式兼容 CLI 使用说明 / Combined compatibility CLI usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

### 项目简介

devpulse 是一个面向开发者和运维工程师的本地 DevOps 工具箱，目标是用 `.devpulse.yaml` 中的 YAML 配置描述用户自定义脚本、CLI 任务、依赖和流水线，无需搭建远程 CI/CD 平台。

**当前状态**：Pulse 独立工具实现 `init`、`pulse`、`review`、`status` 和 `stop`，支持普通任务和本地长驻进程。Gather 与 Jira 是独立工具；组合式 CLI 暂保留所有原有子命令。工具之间不相互依赖，也不内置 AI 调用；仅执行显式配置的长驻进程就绪检查，不自动探测 Docker 等工具的守护进程。

### 环境与构建

开发、构建和运行均需要 JDK 25 或更高版本，构建使用 Maven。`gather`、实际执行的 `pulse` 和 `status --tools` 还需要可用的 Bash；Windows 使用 Git Bash，不使用 CMD、PowerShell 或 WSL 替代 Bash。`init`、`review`、`pulse --dry-run`、历史列表和日志读取不需要 Bash。长驻进程的就绪检查及自定义停止命令使用启动时记录的 Bash 路径，不重新加载项目配置。

在仓库根目录构建并查看帮助：

```powershell
bash build.sh --clean
java -jar devpulse-cli\target\devpulse.jar --help
```

组合入口生成 `devpulse-cli\target\devpulse.jar`，不进入单工具发行包。以下示例使用组合入口保持原有命令用法；单独运行 Pulse 请使用 `java -jar devpulse-plugins\pulse\target\devpulse-pulse.jar [TARGET]`，相关命令通过 `pulse init|review|status|stop` 调用。模块组织见 [架构导航](../docs/architecture.md)，安装说明见 [发行与安装](../devpulse-dist/README.md)。Git Bash 中 `bash run.sh --help` 使用组合入口，`bash run-jira.sh --help` 只构建 Jira；脚本仅在其子进程中移除 `JAVA_TOOL_OPTIONS`。

### 命令概览与帮助

| 命令 | 用途 | 当前状态 |
|------|------|----------|
| `init` | 写入固定的配置模板，供你手动修改 | 已实现 |
| `gather` | 盘点本地环境并生成提示词，供你交给 AI 辅助编写配置 | 已实现 |
| `pulse` | 按依赖和并发上限执行任务或流水线 | 已实现 |
| `review` | 静态校验并审阅执行计划，不运行任务 | 已实现 |
| `status` | 查询运行历史、日志及长驻进程的存活与就绪状态，或检查工具可用性 | 已实现 |
| `stop` | 手动停止指定运行中的长驻进程 | 已实现 |
| 独立的 `devpulse-jira` | 从 HOCON/Markdown 模板创建 Jira Issue、导出与更新、流转、评论和附件；详见 [Jira CLI](../devpulse-plugins/jira/README.md) | 已实现 |

不指定子命令时显示主命令帮助。主命令和所有子命令均支持 `-h` / `--help` 和 `-V` / `--version`。

```powershell
java -jar devpulse-cli\target\devpulse.jar
java -jar devpulse-cli\target\devpulse.jar --version
java -jar devpulse-cli\target\devpulse.jar gather --help
```

### 各命令使用说明

工作流命令与 YAML 配置见 [Pulse](../devpulse-plugins/pulse/README.md)，环境盘点与 AI 提示词见 [Gather](../devpulse-plugins/gather/README.md)。Jira 与 Depmap 不属于组合入口，请分别参阅 [Jira](../devpulse-plugins/jira/README.md) 和 [Depmap](../devpulse-plugins/depmap/README.md)。

[架构设计](../docs/devpulse-cli/architecture.md)

## English

[Project home](../readme.MD)

### Overview

devpulse is a local DevOps toolbox for developers and operations engineers. It aims to describe user-defined scripts, CLI tasks, dependencies, and pipelines using YAML in `.devpulse.yaml`, without requiring a remote CI/CD platform.

**Current status**: The independent Pulse tool implements `init`, `pulse`, `review`, `status`, and `stop`, supporting batch tasks and local long-running processes. Gather and Jira are independent tools; the combined compatibility CLI retains all former subcommands. The tools do not depend on one another and have no built-in AI calls. Pulse runs explicitly configured daemon readiness checks, not automatic health checks for tool daemons such as Docker.

### Requirements and build

Development, builds, and runtime require JDK 25 or later. Builds use Maven. `gather`, actual `pulse` execution, and `status --tools` also require working Bash; on Windows, use Git Bash rather than CMD, PowerShell, or WSL as a Bash replacement. `init`, `review`, `pulse --dry-run`, history lists, and log reads do not require Bash. Daemon readiness checks and custom stop commands use the Bash path recorded at startup without reloading project configuration.

Build and view help from the repository root:

```powershell
bash build.sh --clean
java -jar devpulse-cli\target\devpulse.jar --help
```

The combined entry point produces `devpulse-cli\target\devpulse.jar` and is not included in individual tool archives. The examples below use it to preserve existing command syntax. To run Pulse alone, use `java -jar devpulse-plugins\pulse\target\devpulse-pulse.jar [TARGET]`; its related commands are `pulse init|review|status|stop`. See the [architecture index](../docs/architecture.md) for modules and [distribution and installation](../devpulse-dist/README.md) for installation. In Git Bash, `bash run.sh --help` launches the combined entry point, while `bash run-jira.sh --help` builds only Jira. Scripts remove `JAVA_TOOL_OPTIONS` only in their child processes.

### Command overview and help

| Command | Purpose | Current status |
|---------|---------|----------------|
| `init` | Write a fixed configuration template for manual editing | Implemented |
| `gather` | Inventory the local environment and generate a prompt for you to share with AI for configuration assistance | Implemented |
| `pulse` | Run tasks or pipelines with dependencies and a concurrency limit | Implemented |
| `review` | Validate and review execution plans without running tasks | Implemented |
| `status` | Query history, logs, daemon liveness/readiness, or tool availability | Implemented |
| `stop` | Manually stop daemons in a selected run | Implemented |
| Standalone `devpulse-jira` | Create Jira issues from HOCON/Markdown templates, export and update, transition, comment, and attach files; see [Jira CLI](../devpulse-plugins/jira/README.md) | Implemented |

Running without a subcommand displays the main command help. The main command and all subcommands support `-h` / `--help` and `-V` / `--version`.

```powershell
java -jar devpulse-cli\target\devpulse.jar
java -jar devpulse-cli\target\devpulse.jar --version
java -jar devpulse-cli\target\devpulse.jar gather --help
```

### Command guides

See [Pulse](../devpulse-plugins/pulse/README.md) for workflow commands and YAML configuration, and [Gather](../devpulse-plugins/gather/README.md) for inventory and AI prompts. Jira and Depmap are not part of the combined entry point; see [Jira](../devpulse-plugins/jira/README.md) and [Depmap](../devpulse-plugins/depmap/README.md) separately.

[Architecture](../docs/devpulse-cli/architecture.md)
