# 兼容 CLI 架构设计 / Compatibility CLI architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [使用说明](../../devpulse-cli/README.md)

### 职责与依赖

`DevPulseCLI` 保留 `devpulse <子命令>` 的组合式入口，复用 Pulse 的 `init`、`pulse`、`review`、`status`、`stop` 和 Gather 的 `gather` 命令。它只负责 picocli 参数分发，不重复实现配置、调度或环境盘点，也不包含 Jira 或 Depmap。

该模块直接依赖 `pulse` 和 `gather`，继承 `devpulse-dependencies`。独立工具之间不相互依赖；只有兼容入口将它们组合。共享库不注册命令，入口不动态发现或加载插件。

### 打包与生命周期

Shade 在 `package` 阶段生成 `devpulse-cli/target/devpulse.jar`，设置 `DevPulseCLI` 为 `Main-Class`，合并服务文件及 `reference.conf`。Manifest 的 `Implementation-Version` 使用项目版本。该产物仅供兼容使用，不进入单工具发行包。

工作流执行与终端生命周期仍由 Pulse 命令负责，环境提示词与剪贴板由 Gather 命令负责；此模块不建立第二套运行状态。

### 原有实施路线图

- 按仓库约定建立 Maven 构建与 picocli 命令骨架。
- 确保可执行 JAR 能通过 `--help` 输出命令帮助。

## English

[Architecture index](../architecture.md) | [Usage](../../devpulse-cli/README.md)

### Responsibilities and dependencies

`DevPulseCLI` preserves the combined `devpulse <subcommand>` entry point, reusing Pulse's `init`, `pulse`, `review`, `status`, and `stop`, plus Gather's `gather`. It only dispatches picocli arguments rather than reimplementing configuration, scheduling, or inventory, and includes neither Jira nor Depmap.

The module directly depends on `pulse` and `gather` and inherits `devpulse-dependencies`. Standalone tools do not depend on one another; only this compatibility entry point combines them. The shared library registers no commands, and the entry point neither discovers nor loads plugins dynamically.

### Packaging and lifecycle

Shade produces `devpulse-cli/target/devpulse.jar` during `package`, sets `DevPulseCLI` as `Main-Class`, and merges service files and `reference.conf`. The manifest's `Implementation-Version` uses the project version. This artifact is for compatibility only and is excluded from individual tool archives.

Pulse commands retain ownership of workflow execution and terminal lifecycle; Gather commands own prompts and clipboard output. This module creates no second execution-state model.

### Original implementation roadmap

- Establish Maven builds and the picocli command skeleton following repository conventions.
- Ensure the executable JAR prints command help through `--help`.
