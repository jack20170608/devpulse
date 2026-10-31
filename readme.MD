# devpulse

[中文](#中文) | [English](#english)

## 中文

### 项目简介

devpulse 是一个面向开发者和运维工程师的本地 DevOps 工具箱，目标是用 `.devpulse.conf` 中的 HOCON 配置描述脚本、CLI 任务、依赖和流水线，无需搭建远程 CI/CD 平台。

**当前状态**：`init` 和 `gather` 已实现；`pulse` 和 `status` 仅有命令入口，尚未实现流水线执行或工具健康检查。下文区分现有行为与规划功能。

### 环境与构建

开发、构建和运行均需要 JDK 25 或更高版本，构建使用 Maven。`gather` 还需要可用的 Bash；Windows 使用 Git Bash，不使用 CMD、PowerShell 或 WSL 替代 Bash。

在仓库根目录构建并查看帮助：

```powershell
mvn clean package
java -jar target\devpulse.jar --help
```

产物为包含运行依赖的 `target\devpulse.jar`。以下示例用 PowerShell 启动 Java；`gather` 内部仍通过 Bash 盘点工具。

### 命令概览与帮助

| 命令 | 用途 | 当前状态 |
|------|------|----------|
| `init` | 写入固定的配置模板，供你手动修改 | 已实现 |
| `gather` | 盘点本地环境并生成提示词，供你交给 AI 辅助编写配置 | 已实现 |
| `pulse` | 按任务依赖执行本地流水线 | 尚未实现 |
| `status` | 查看本地工具的健康与集成状态 | 尚未实现 |

不指定子命令时显示主命令帮助。主命令和所有子命令均支持 `-h` / `--help` 和 `-V` / `--version`。

```powershell
java -jar target\devpulse.jar
java -jar target\devpulse.jar --version
java -jar target\devpulse.jar gather --help
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
java -jar target\devpulse.jar init

# 在已有项目目录创建配置模板
java -jar target\devpulse.jar init --directory "D:\your-project"

# 覆盖已有配置，原内容将丢失
java -jar target\devpulse.jar init --directory "D:\your-project" --force
```

成功时输出配置文件路径并返回退出码 `0`。目录不存在、配置已存在且未指定 `--force`，或文件无法写入时，输出错误并返回 `1`。

### `gather`：生成辅助编写配置的 AI 提示词

**适用场景**：你希望 AI 根据本机可用工具和项目类型，辅助编写 `.devpulse.conf`，而不是从空白开始。

`gather` 收集以下事实，组装为中英文 Markdown 提示词并输出到终端，默认还会复制到剪贴板：

- 项目的绝对路径、操作系统、架构、当前 Java 版本和 Bash 路径。
- 固定工具清单的查找结果：`git`、`docker`、`kubectl`、`helm`、`node`、`npm`、`pnpm`、`yarn`、`java`、`mvn`、`gradle`、`python`、`python3`、`go`、`cargo`、`make`、`terraform`。通过 Bash 的 `command -v` 记录位置，未找到的工具标为缺失。
- 项目根目录中的标志是否存在，例如 `.git`、`package.json`、`pom.xml`、Gradle 构建文件、`Dockerfile`、Compose 配置、Python 依赖文件、`go.mod`、`Cargo.toml`、`Makefile` 和 `.devpulse.conf`。

提示词包含 HOCON 示例、任务依赖要求、Bash 执行约束和命令审核提醒，要求 AI 不捏造项目脚本、信息不足时说明假设，并避免默认推荐安装、删除、部署或推送等操作。

**能力边界**：不读取项目文件内容、不递归扫描、不采集环境变量值，也不执行工具版本或健康检查。找到工具不代表工具配置正确或服务可用。`gather` 不会自动调用 AI、生成或写入 `.devpulse.conf`，也不会执行项目任务。

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 指定盘点的项目目录，默认为当前目录；目录必须已经存在 |
| `--bash <路径>` | 显式指定 Bash 可执行文件；Windows 应指定 Git Bash |
| `--no-clipboard` | 仅输出提示词，不修改剪贴板 |

```powershell
# 盘点当前目录，输出提示词并尝试复制到剪贴板
java -jar target\devpulse.jar gather

# 盘点指定项目，不修改剪贴板
java -jar target\devpulse.jar gather --directory "D:\your-project" --no-clipboard

# 显式指定 Git Bash
java -jar target\devpulse.jar gather --bash "C:\Program Files\Git\bin\bash.exe"
```

成功时返回 `0`。剪贴板不可用时会输出警告，提示词仍保留在终端，不影响盘点成功。目录无效、找不到可用 Bash、扫描失败或被中断时返回 `1`。

提示词包含本地项目路径和工具位置，发送给外部 AI 前应先审核。由于未读取项目文件，通常还需补充实际脚本和命令；AI 生成的配置也必须人工审核，不能直接视为可靠的执行结果。

### `pulse`：执行流水线（尚未实现）

**规划用途**：读取配置，按照任务依赖关系调度本地流水线，让依赖允许的任务并发执行。

**当前行为**：仅输出 `pulse is not implemented yet.` 并返回 `1`，不会读取配置、调度任务或执行流水线。目前没有任务或流水线选择参数，不能使用 `pulse [task]`。

```powershell
java -jar target\devpulse.jar pulse
```

即使已经准备好 `.devpulse.conf`，当前版本也不能通过 `pulse` 执行它。

### `status`：查看工具状态（尚未实现）

**规划用途**：展示本地工具的健康与集成状态，帮助判断开发环境是否可用。

**当前行为**：仅输出 `status is not implemented yet.` 并返回 `1`，不会探测工具、检查服务或输出健康报告。目前没有工具筛选等业务参数。

```powershell
java -jar target\devpulse.jar status
```

`gather` 的工具查找结果不能替代健康检查；例如，找到 `docker` 可执行文件并不说明 Docker 服务正在运行。

### 配置准备工作流

自己编写配置时，运行 `init`，然后修改模板中的任务命令。需要 AI 辅助时，按以下流程操作；这条路径不要求先运行 `init`：

1. 运行 `gather`，获取环境提示词。
2. 审核提示词后，将其交给你选择的本地或云端 AI，并补充实际项目脚本和需求。
3. 审核 AI 生成的 HOCON 配置，手动保存为项目目录下的 `.devpulse.conf`。

devpulse 不内置 AI 调用；是否向外部服务分享提示词由你决定。**当前上述流程只完成配置准备，流水线执行和健康检查仍需等待 `pulse`、`status` 实现。**

### 配置示例（`.devpulse.conf`）

配置采用 HOCON，不支持 YAML。以下示例与 `init` 模板一致，用于说明任务、依赖和流水线的结构，并非已经发现的项目任务，也不代表当前版本能够执行它：

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

### 开源协议

本项目基于 [MIT License](LICENSE) 协议开源。

## English

### Overview

devpulse is a local DevOps toolbox for developers and operations engineers. It aims to describe scripts, CLI tasks, dependencies, and pipelines using HOCON in `.devpulse.conf`, without requiring a remote CI/CD platform.

**Current status**: `init` and `gather` are implemented. `pulse` and `status` only have command entry points; pipeline execution and tool health checks are not implemented. The descriptions below distinguish current behavior from planned functionality.

### Requirements and build

Development, builds, and runtime require JDK 25 or later. Builds use Maven. `gather` additionally requires working Bash; on Windows, use Git Bash rather than CMD, PowerShell, or WSL as a Bash replacement.

Build and view help from the repository root:

```powershell
mvn clean package
java -jar target\devpulse.jar --help
```

The artifact is `target\devpulse.jar`, including runtime dependencies. The examples below use PowerShell to launch Java; `gather` still uses Bash internally to locate tools.

### Command overview and help

| Command | Purpose | Current status |
|---------|---------|----------------|
| `init` | Write a fixed configuration template for manual editing | Implemented |
| `gather` | Inventory the local environment and generate a prompt for you to share with AI for configuration assistance | Implemented |
| `pulse` | Run local pipelines according to task dependencies | Not implemented |
| `status` | Show local tool health and integration status | Not implemented |

Running without a subcommand displays the main command help. The main command and all subcommands support `-h` / `--help` and `-V` / `--version`.

```powershell
java -jar target\devpulse.jar
java -jar target\devpulse.jar --version
java -jar target\devpulse.jar gather --help
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
java -jar target\devpulse.jar init

# Create a configuration template in an existing project directory
java -jar target\devpulse.jar init --directory "D:\your-project"

# Overwrite an existing configuration, losing its original contents
java -jar target\devpulse.jar init --directory "D:\your-project" --force
```

On success, the command prints the configuration path and returns exit code `0`. A missing directory, an existing configuration without `--force`, or a write failure produces an error and returns `1`.

### `gather`: generate an AI prompt for configuration assistance

**When to use it**: You want AI to help write `.devpulse.conf` based on locally available tools and project markers rather than starting from scratch.

`gather` collects the following facts, formats them as a Chinese/English Markdown prompt, prints it to the terminal, and also copies it to the clipboard by default:

- The absolute project path, operating system, architecture, current Java version, and Bash path.
- Lookup results for a fixed tool list: `git`, `docker`, `kubectl`, `helm`, `node`, `npm`, `pnpm`, `yarn`, `java`, `mvn`, `gradle`, `python`, `python3`, `go`, `cargo`, `make`, and `terraform`. Bash's `command -v` records their locations; tools that cannot be found are marked as missing.
- Whether project markers exist in the project root, such as `.git`, `package.json`, `pom.xml`, Gradle build files, `Dockerfile`, Compose configurations, Python dependency files, `go.mod`, `Cargo.toml`, `Makefile`, and `.devpulse.conf`.

The prompt includes a HOCON example, task dependency requirements, Bash execution constraints, and command review reminders. It asks AI not to invent project scripts, to state assumptions when information is insufficient, and to avoid recommending installation, deletion, deployment, or pushing by default.

**Limitations**: It does not read project file contents, scan recursively, collect environment variable values, or run tool version or health checks. Finding a tool does not establish correct configuration or service availability. `gather` does not automatically call AI, generate or write `.devpulse.conf`, or execute project tasks.

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Select the project to inspect; defaults to the current directory, which must already exist |
| `--bash <path>` | Explicitly select the Bash executable; on Windows, select Git Bash |
| `--no-clipboard` | Print the prompt without changing the clipboard |

```powershell
# Inspect the current directory, print the prompt, and try to copy it to the clipboard
java -jar target\devpulse.jar gather

# Inspect a selected project without changing the clipboard
java -jar target\devpulse.jar gather --directory "D:\your-project" --no-clipboard

# Explicitly select Git Bash
java -jar target\devpulse.jar gather --bash "C:\Program Files\Git\bin\bash.exe"
```

Success returns `0`. If the clipboard is unavailable, the command prints a warning and leaves the prompt in the terminal; the inventory still succeeds. An invalid directory, missing working Bash, failed scan, or interruption returns `1`.

The prompt contains local project paths and tool locations; review it before sharing with external AI. Since project files are not read, you will usually need to supply actual scripts and commands as additional context. AI-generated configuration also requires human review and must not be assumed reliable for execution.

### `pulse`: run pipelines (not implemented)

**Planned purpose**: Read the configuration and schedule local pipelines according to task dependencies, running tasks concurrently when dependencies allow.

**Current behavior**: Only prints `pulse is not implemented yet.` and returns `1`. It does not read configuration, schedule tasks, or run pipelines. There are no task or pipeline selection arguments yet; `pulse [task]` is not supported.

```powershell
java -jar target\devpulse.jar pulse
```

Even with `.devpulse.conf` prepared, the current version cannot execute it using `pulse`.

### `status`: inspect tool status (not implemented)

**Planned purpose**: Show local tool health and integration status to help determine whether the development environment is usable.

**Current behavior**: Only prints `status is not implemented yet.` and returns `1`. It does not probe tools, check services, or produce a health report. There are no business options such as tool filtering yet.

```powershell
java -jar target\devpulse.jar status
```

Tool lookup results from `gather` are not a substitute for health checks; for example, finding the `docker` executable does not establish that the Docker service is running.

### Configuration preparation workflow

To write configuration yourself, run `init` and edit the template's task commands. For AI assistance, follow this workflow; it does not require running `init` first:

1. Run `gather` to obtain the environment prompt.
2. Review the prompt, share it with your chosen local or cloud AI, and supply actual project scripts and requirements.
3. Review the AI-generated HOCON configuration and manually save it as `.devpulse.conf` in the project directory.

devpulse does not include AI calls; you decide whether to share the prompt with external services. **These workflows currently only prepare configuration. Pipeline execution and health checks must wait for `pulse` and `status` to be implemented.**

### Configuration example (`.devpulse.conf`)

Configuration uses HOCON, not YAML. The following example matches the `init` template and illustrates tasks, dependencies, and pipelines. It does not represent discovered project tasks or imply that the current version can execute it:

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

### License

This project is released under the [MIT License](LICENSE).
