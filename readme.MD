# devpulse

[中文](#中文) | [English](#english)

## 中文

devpulse 是基于 Java 25 和 Maven 的本地 DevOps 工具箱。核心 CLI 使用 HOCON 配置描述任务和依赖，通过 Bash 执行；Jira 是独立运行的插件工具，核心不加载或依赖它。

### 快速开始

在仓库根目录运行（Windows 使用 Git Bash）：

```bash
mvn clean package
bash run.sh --help
bash run-jira.sh --help
```

`run.sh` 仅构建并运行核心，`run-jira.sh` 仅构建并运行 Jira；两个脚本不会修改父终端的环境变量。核心构建产物为 `devpulse-core/target/devpulse.jar`，Jira 为 `devpulse-plugins/jira/target/devpulse-jira.jar`。运行 JAR 需要 Java 25 或更高版本；实际执行 `pulse`、`gather` 和 `status --tools` 还需要 Bash。

### 文档导航

| 文档 | 内容 |
|------|------|
| [核心 CLI 使用指南](docs/core-cli.md) | 安装要求、配置示例、`init` / `gather` / `pulse` / `review` / `status` |
| [Jira 插件指南](docs/plugins/jira.md) | 认证、模板、Issue 操作与 Markdown 边界 |
| [架构与实现设计](docs/architecture.md) | 模块组织、配置、调度、持久化及设计约束 |
| [Copilot 仓库指令](.github/copilot-instructions.md) | 技术栈、编码、文档及构建规范 |

### Maven 模块

```text
devpulse-dependencies/   外部依赖版本
devpulse-bom/            内部产物版本
devpulse-core/           核心 CLI
devpulse-plugins/        插件聚合模块
  jira/                  独立的 Jira CLI
```

`devpulse-dependencies` 和 `devpulse-bom` 只生成 POM。工具输出和 AI 生成的命令应先审核；devpulse 不提供沙箱，也不内置 AI 调用。

## English

devpulse is a local DevOps toolbox built with Java 25 and Maven. The core CLI uses HOCON to describe tasks and dependencies and runs them through Bash. Jira is a standalone plugin tool; core neither loads nor depends on it.

### Quick start

From the repository root (use Git Bash on Windows):

```bash
mvn clean package
bash run.sh --help
bash run-jira.sh --help
```

`run.sh` builds and launches only core; `run-jira.sh` builds and launches only Jira. Neither script changes the parent terminal's environment. Core builds `devpulse-core/target/devpulse.jar`; Jira builds `devpulse-plugins/jira/target/devpulse-jira.jar`. Running either JAR requires Java 25 or later; actual `pulse` execution, `gather`, and `status --tools` also require Bash.

### Documentation

| Document | Contents |
|----------|----------|
| [Core CLI guide](docs/core-cli.md) | Requirements, configuration example, `init` / `gather` / `pulse` / `review` / `status` |
| [Jira plugin guide](docs/plugins/jira.md) | Authentication, templates, issue operations, and Markdown limits |
| [Architecture and implementation](docs/architecture.md) | Modules, configuration, scheduling, persistence, and design constraints |
| [Copilot repository instructions](.github/copilot-instructions.md) | Technology stack, coding, documentation, and build conventions |

### Maven modules

```text
devpulse-dependencies/   External dependency versions
devpulse-bom/            Internal artifact versions
devpulse-core/           Core CLI
devpulse-plugins/        Plugin aggregator
  jira/                  Standalone Jira CLI
```

`devpulse-dependencies` and `devpulse-bom` produce POMs only. Review tool output and AI-generated commands before using them; devpulse is not a sandbox and does not include AI calls.
