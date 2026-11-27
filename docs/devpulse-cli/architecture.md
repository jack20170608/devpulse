# devpulse CLI 架构设计 / Compatibility CLI architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [使用说明](../../devpulse-cli/README.md)

### 职责与依赖

`DevPulseCLI` 是 `devpulse <子命令>` 的唯一入口，内含 Pulse 的 `init`、`run`、`review`、`status`、`stop`、`doctor` 与服务端的 `server` 子命令。它只负责 picocli 参数分发，不重复实现配置、调度或环境盘点，也不包含 Jira。

该模块依赖 `devpulse-core`、`devpulse-execution` 与 `devpulse-server`，继承 `devpulse-dependencies`。工具之间不相互依赖；共享库不注册命令，入口不动态发现或加载插件。

### 打包与生命周期

Shade 在 `package` 阶段生成 `devpulse-cli/target/devpulse.jar`，设置 `DevPulseCLI` 为 `Main-Class`，合并服务文件及 `reference.conf`。Manifest 的 `Implementation-Version` 使用项目版本。该产物即发行与原生镜像使用的唯一入口。

工作流执行与终端生命周期仍由 `run` 命令负责；此模块不建立第二套运行状态。Pulse 命令层的细节见 [Pulse 命令层](pulse-commands-architecture.md)。

### 原有实施路线图

- 按仓库约定建立 Maven 构建与 picocli 命令骨架。
- 确保可执行 JAR 能通过 `--help` 输出命令帮助。

## English

[Architecture index](../architecture.md) | [Usage](../../devpulse-cli/README.md)

### Responsibilities and dependencies

`DevPulseCLI` is the single `devpulse <subcommand>` entry point, containing Pulse's `init`, `run`, `review`, `status`, `stop`, `doctor` and the server's `server` subcommand. It only dispatches picocli arguments rather than reimplementing configuration, scheduling, or inventory, and does not include Jira.

The module depends on `devpulse-core`, `devpulse-execution` and `devpulse-server`, and inherits `devpulse-dependencies`. Tools do not depend on one another; shared libraries register no commands, and the entry point neither discovers nor loads plugins dynamically.

### Packaging and lifecycle

Shade produces `devpulse-cli/target/devpulse.jar` during `package`, sets `DevPulseCLI` as `Main-Class`, and merges service files and `reference.conf`. The manifest's `Implementation-Version` uses the project version. This artifact is the single binary used by release archives and the native image.

The `run` command retains ownership of workflow execution and terminal lifecycle. This module creates no second execution-state model. Details of the command layer are in [Pulse command layer](pulse-commands-architecture.md).

### Original implementation roadmap

- Establish Maven builds and the picocli command skeleton following repository conventions.
- Ensure the executable JAR prints command help through `--help`.
