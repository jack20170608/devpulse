# devpulse CLI 使用说明 / devpulse CLI usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

### 项目简介

devpulse 是一个面向开发者和运维工程师的本地 DevOps 工具箱，目标是用 `.devpulse.yaml` 中的 YAML 配置描述用户自定义脚本、CLI 任务、依赖和流水线，无需搭建远程 CI/CD 平台。

**当前状态**：`devpulse` 提供 `init`、`run`、`review`、`status`、`stop`、`doctor` 与 `server` 七个命令，支持普通任务、本地长驻进程和本地 Web 服务端。Jira 是独立工具。工具之间不相互依赖，也不内置 AI 调用；仅执行显式配置的长驻进程就绪检查，不自动探测 Docker 等工具的守护进程。

### 环境与构建

开发、构建和运行均需要 JDK 21 或更高版本，构建使用 Maven。实际执行的 `run` 和 `doctor` 还需要可用的 Bash；Windows 使用 Git Bash，不使用 CMD、PowerShell 或 WSL 替代 Bash。`init`、`review`、`run --dry-run`、历史列表和日志读取不需要 Bash。长驻进程的就绪检查及自定义停止命令使用启动时记录的 Bash 路径，不重新加载项目配置。

在仓库根目录构建并查看帮助：

```powershell
bash build.sh --clean
java -jar devpulse-cli\target\devpulse.jar --help
```

CLI 产物为 `devpulse-cli\target\devpulse.jar`，它同时是发行包与原生镜像使用的唯一入口。模块组织见 [架构导航](../docs/architecture.md)，安装说明见 [发行与安装](../devpulse-dist/README.md)。Git Bash 中 `bash run.sh --help` 构建并运行 CLI，`bash integration-test/jira/run-jira.sh --help` 只运行已构建的 Jira；脚本仅在其子进程中移除 `JAVA_TOOL_OPTIONS`。

### 原生可执行文件（可选）

除 JAR 之外，还可以用 GraalVM 的 `native-image` 构建单文件原生可执行程序，把 `--version`、`--help` 等短命令的启动耗时从约 300 ms 降到 120 ms 左右（实测）。它是额外的产物，JAR 仍是功能完整的兜底入口，两者并存。

```bash
GRAALVM_HOME=/path/to/graalvm bash native.sh            # 只构建
GRAALVM_HOME=/path/to/graalvm bash native.sh --verify   # 构建并运行冒烟检查
```

产物为 `devpulse-cli/target/devpulse-native`，大小约 69 MB。`--verify` 覆盖版本、帮助、流水线执行、`status`、`review`、长驻进程生命周期、`stop`，以及未知目标（`12`）和任务失败（`30`）两个分类退出码：原生镜像缺少反射配置时编译仍然成功，只有运行到该路径才报错，因此冒烟检查是这层产物的真正门禁。反射与资源元数据位于 `devpulse-cli/src/main/resources/META-INF/native-image/`，由 GraalVM agent 在 Linux x64 上录制；在其它平台构建时应先在目标平台重新录制。新增 classpath 资源（例如数据库迁移 SQL）时必须在本目录元数据的 `resources` 里登记：用 `glob` 形式，`pattern` 写法在当前 GraalVM 版本下不生效；漏登记的后果是原生版运行到读取该资源时才崩溃。链接阶段需要系统的 zlib 开发库（如 Debian/Ubuntu 的 `zlib1g-dev`）。

### 命令概览与帮助

| 命令 | 用途 | 当前状态 |
|------|------|----------|
| `init` | 写入固定的配置模板，供你手动修改 | 已实现 |
| `run` | 按依赖和并发上限执行任务或流水线 | 已实现 |
| `review` | 静态校验并审阅执行计划，不运行任务 | 已实现 |
| `status` | 查询运行历史、日志及长驻进程的存活与就绪状态 | 已实现 |
| `server` | 本地 Web 服务端：`start`\|`run`\|`status`\|`stop`，默认 12580 | 已实现 |
| `stop` | 手动停止指定运行中的长驻进程 | 已实现 |
| 独立的 `devpulse-jira` | 从 HOCON/Markdown 模板创建 Jira Issue、导出与更新、流转、评论和附件；详见 [Jira CLI](../devpulse-tools/jira/README.md) | 已实现 |

不指定子命令时显示主命令帮助。主命令和所有子命令均支持 `-h` / `--help` 和 `-V` / `--version`。

```powershell
java -jar devpulse-cli\target\devpulse.jar
java -jar devpulse-cli\target\devpulse.jar --version
```

### 各命令使用说明

工作流命令与 YAML 配置见 [Pulse 命令层](../docs/devpulse-cli/pulse-commands.md)。Jira 不在 CLI 中，请参阅 [Jira](../devpulse-tools/jira/README.md)。

[架构设计](../docs/devpulse-cli/architecture.md)

## English

[Project home](../readme.MD)

### Overview

devpulse is a local DevOps toolbox for developers and operations engineers. It aims to describe user-defined scripts, CLI tasks, dependencies, and pipelines using YAML in `.devpulse.yaml`, without requiring a remote CI/CD platform.

**Current status**: `devpulse` provides seven commands: `init`, `run`, `review`, `status`, `stop`, `doctor`, and `server`, supporting batch tasks, local long-running processes, and the local web server. Jira is an independent tool. The tools do not depend on one another and have no built-in AI calls. Pulse runs explicitly configured daemon readiness checks, not automatic health checks for tool daemons such as Docker.

### Requirements and build

Development, builds, and runtime require JDK 21 or later. Builds use Maven. actual `run` execution, and `doctor` also require working Bash; on Windows, use Git Bash rather than CMD, PowerShell, or WSL as a Bash replacement. `init`, `review`, `run --dry-run`, history lists, and log reads do not require Bash. Daemon readiness checks and custom stop commands use the Bash path recorded at startup without reloading project configuration.

Build and view help from the repository root:

```powershell
bash build.sh --clean
java -jar devpulse-cli\target\devpulse.jar --help
```

The CLI artifact is `devpulse-cli\target\devpulse.jar`; it is the single entry point used by release archives and the native image. See the [architecture index](../docs/architecture.md) for modules and [distribution and installation](../devpulse-dist/README.md) for installation. In Git Bash, `bash run.sh --help` builds and runs the CLI, while `bash integration-test/jira/run-jira.sh --help` runs the built Jira JAR. Scripts remove `JAVA_TOOL_OPTIONS` only in their child processes.

### Native executable (optional)

Besides the JAR, GraalVM `native-image` can build a single-file native executable that starts short commands such as `--version` and `--help` in about 120 ms instead of roughly 300 ms (measured). It is an additional artifact; the JAR stays the complete fallback, and the two coexist.

```bash
GRAALVM_HOME=/path/to/graalvm bash native.sh            # build only
GRAALVM_HOME=/path/to/graalvm bash native.sh --verify   # build and run the smoke checks
```

The output is `devpulse-cli/target/devpulse-native`, about 69 MB. `--verify` covers version, help, pipeline execution, `status`, `review`, a daemon lifecycle, `stop`, and the two classified exit codes for an unknown target (`12`) and a failing task (`30`). A native image whose reflection metadata is incomplete still compiles; it fails only when that path runs, so the smoke checks are the real gate for this artifact. The reflection and resource metadata lives in `devpulse-cli/src/main/resources/META-INF/native-image/` and was recorded by the GraalVM agent on Linux x64; record it again on any other target platform. A new classpath resource (a database migration, for example) must be registered in this directory's metadata `resources`: use the `glob` form, because `pattern` does not take effect with the current GraalVM version here, and an unregistered resource only fails when the native binary reads it. Linking needs the system zlib development library (for example `zlib1g-dev` on Debian/Ubuntu).

### Command overview and help

| Command | Purpose | Current status |
|---------|---------|----------------|
| `init` | Write a fixed configuration template for manual editing | Implemented |
| `run` | Run tasks or pipelines with dependencies and a concurrency limit | Implemented |
| `review` | Validate and review execution plans without running tasks | Implemented |
| `status` | Query history, logs, and daemon liveness/readiness | Implemented |
| `stop` | Manually stop daemons in a selected run | Implemented |
| `server` | Local web server: `start`\|`run`\|`status`\|`stop`, port 12580 by default | Implemented |
| Standalone `devpulse-jira` | Create Jira issues from HOCON/Markdown templates, export and update, transition, comment, and attach files; see [Jira CLI](../devpulse-tools/jira/README.md) | Implemented |

Running without a subcommand displays the main command help. The main command and all subcommands support `-h` / `--help` and `-V` / `--version`.

```powershell
java -jar devpulse-cli\target\devpulse.jar
java -jar devpulse-cli\target\devpulse.jar --version
```

### Command guides

See the [Pulse command layer](../docs/devpulse-cli/pulse-commands.md) for workflow commands and YAML configuration. Jira is not part of the CLI; see [Jira](../devpulse-tools/jira/README.md).

[Architecture](../docs/devpulse-cli/architecture.md)
