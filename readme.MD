# devpulse

[中文](#中文) | [English](#english)

## 中文

devpulse 是基于 Java 25 和 Maven 的本地 DevOps 工具箱。Pulse 使用 HOCON 配置描述任务和依赖，通过 Bash 执行；Jira 独立运行。两者可以分别安装。

### 快速开始

在仓库根目录运行（Windows 使用 Git Bash）：

```bash
bash build.sh --clean
bash run.sh --help
bash run-jira.sh --help
java -jar devpulse-plugins/pulse/target/devpulse-pulse.jar --help
```

`build.sh` 默认构建全部模块，也支持 `bash build.sh pulse|gather|jira|cli|dist` 按需构建，`--clean` 可清理所选模块的旧产物。`run.sh` 保留组合式 CLI 兼容入口；`run-jira.sh` 只构建和运行 Jira。两个启动脚本都复用 `build.sh`，不会修改父终端的环境变量。可选安装与发行包见 [发行与安装](docs/distribution.md)。运行 JAR 需要 Java 25 或更高版本；实际执行 `pulse`、`gather` 和 `status --tools` 还需要 Bash。

### 文档导航

| 文档 | 内容 |
|------|------|
| [核心 CLI 使用指南](docs/core-cli.md) | 安装要求、配置示例、`init` / `gather` / `pulse` / `review` / `status` / `stop` 及本地长驻进程 |
| [发行与安装](docs/distribution.md) | 按需安装 Pulse 或 Jira、升级与卸载 |
| [Jira 插件指南](docs/plugins/jira.md) | 认证、模板、Issue 操作与 Markdown 边界 |
| [架构与实现设计](docs/architecture.md) | 模块组织、配置、调度、持久化及设计约束 |
| [Copilot 仓库指令](.github/copilot-instructions.md) | 技术栈、编码、文档及构建规范 |

### Maven 模块

```text
devpulse-dependencies/   外部依赖版本
devpulse-bom/            内部产物版本
devpulse-core/           共享环境库
devpulse-plugins/        插件聚合模块
  pulse/                 独立的 Pulse CLI
  gather/                独立的 Gather CLI
  jira/                  独立的 Jira CLI
devpulse-cli/            组合式 CLI 兼容入口
devpulse-dist/           可选工具发行包
```

项目版本仅在根 `pom.xml` 的 `<revision>` 中定义；子模块的 `<parent><version>` 使用 `${revision}` 引用，不维护独立版本号。`devpulse-dependencies` 和 `devpulse-bom` 只生成 POM。工具输出和 AI 生成的命令应先审核；devpulse 不提供沙箱，也不内置 AI 调用。

## English

devpulse is a local DevOps toolbox built with Java 25 and Maven. Pulse uses HOCON to describe tasks and dependencies and runs them through Bash. Jira runs independently; the tools can be installed separately.

### Quick start

From the repository root (use Git Bash on Windows):

```bash
bash build.sh --clean
bash run.sh --help
bash run-jira.sh --help
java -jar devpulse-plugins/pulse/target/devpulse-pulse.jar --help
```

`build.sh` builds every module by default, or use `bash build.sh pulse|gather|jira|cli|dist` to build a selected target; `--clean` removes its previous output. `run.sh` preserves the combined CLI for compatibility, while `run-jira.sh` builds and launches only Jira. Both launchers use `build.sh` and do not change the parent shell's environment. See [distribution and installation](docs/distribution.md) for selective installation. Running the JARs requires Java 25 or later; actual `pulse` execution, `gather`, and `status --tools` also require Bash.

### Documentation

| Document | Contents |
|----------|----------|
| [Core CLI guide](docs/core-cli.md) | Requirements, configuration, `init` / `gather` / `pulse` / `review` / `status` / `stop`, and local daemons |
| [Distribution and installation](docs/distribution.md) | Selective Pulse or Jira installation, upgrades, and removal |
| [Jira plugin guide](docs/plugins/jira.md) | Authentication, templates, issue operations, and Markdown limits |
| [Architecture and implementation](docs/architecture.md) | Modules, configuration, scheduling, persistence, and design constraints |
| [Copilot repository instructions](.github/copilot-instructions.md) | Technology stack, coding, documentation, and build conventions |

### Maven modules

```text
devpulse-dependencies/   External dependency versions
devpulse-bom/            Internal artifact versions
devpulse-core/           Shared environment library
devpulse-plugins/        Plugin aggregator
  pulse/                 Standalone Pulse CLI
  gather/                Standalone Gather CLI
  jira/                  Standalone Jira CLI
devpulse-cli/            Combined compatibility CLI
devpulse-dist/           Selective tool archives
```

Define the project version only in the root `pom.xml`'s `<revision>` property; child `<parent><version>` fields reference `${revision}` instead of maintaining separate versions. `devpulse-dependencies` and `devpulse-bom` produce POMs only. Review tool output and AI-generated commands before using them; devpulse is not a sandbox and does not include AI calls.
