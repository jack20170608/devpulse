# devpulse

[中文](#中文) | [English](#english)

## 中文

devpulse 是基于 Java 21 和 Maven 的本地 DevOps 工具箱。`devpulse` 是唯一入口：它读取 `.devpulse.yaml`，通过 Bash 执行任务与依赖；Jira 作为独立工具单独发行。两者的安装与升级见 [发行与安装](devpulse-dist/README.md)。

### 快速开始

在仓库根目录运行（Windows 使用 Git Bash）：

```bash
bash build.sh --clean
bash run.sh --help
bash integration-test/jira/run-jira.sh --help
java -jar devpulse/target/devpulse.jar --help
```

`build.sh` 默认构建全部模块，也支持 `bash build.sh pulse|jira|devpulse|dist` 按需构建，`--clean` 可清理所选模块的旧产物；`dist` 目标始终先清理再打包。`run.sh` 构建并运行统一入口；`integration-test/jira/run-jira.sh` 只运行已构建的 Jira JAR。两者都不会修改父终端的环境变量。可选安装与发行包见 [发行与安装](devpulse-dist/README.md)。运行 JAR 需要 Java 21 或更高版本；实际执行 `run` 和 `doctor` 还需要 Bash。

### 产品定位

devpulse 面向"**本地、无容器、Bash 原生、有状态**"的任务编排：用 YAML 描述任务依赖，由调度器决定顺序与并发，长驻进程按就绪状态放行后继，每次执行留下可回查的记录。它不是 shell 的替代品，也不做容器隔离与远程执行。与 shell 脚本以及 act、Earthly、Dagger、Nx 等工具的边界，以及后续功能取舍的判断标准，见 [产品定位与竞品分析](docs/design/positioning.md)。

### 文档导航

| 文档 | 内容 |
|------|------|
| [Pulse 命令说明](devpulse-pulse/README.md) | 任务配置、执行、审阅、状态与长驻进程 |
| [Core 使用说明](devpulse-core/README.md) | Java DAG Builder、Bash 工作流嵌入与环境采集 |
| [devpulse CLI 使用说明](devpulse/README.md) | 统一入口与命令导航 |
| [发行与安装](devpulse-dist/README.md) | 本地构建安装、升级与卸载 |
| [Jira 使用说明](devpulse-tools/jira/README.md) | 连接配置、Issue 操作与 Markdown 边界 |
| [模块架构导航](docs/architecture.md) | 按 Maven 模块组织的架构设计 |
| [产品定位与竞品分析](docs/design/positioning.md) | 与 shell 的边界、竞品对比与产品设计准则 |
| [产品形态：一个二进制](docs/design/product-shape.md) | 服务端与客户端的分工、命名、YAML 数据化与实施顺序 |
| [服务端接口设计](docs/design/server-api.md) | 进程模型、认证、端点、错误码与事件流 |
| [Copilot 仓库指令](.github/copilot-instructions.md) | 技术栈、编码、文档及构建规范 |

### Maven 模块

```text
devpulse-dependencies/   外部依赖版本
devpulse-bom/            内部产物版本
devpulse-core/           DAG 执行引擎、Bash 与环境采集共享库
devpulse-storage/        运行状态持久化（H2 + JDBI）、schema 迁移与复用索引
devpulse-execution/      执行内核：配置解析、计划、指纹与运行编排
devpulse-cli/            统一 CLI：入口、Pulse 命令与服务端子命令
devpulse-tools/          独立工具聚合
  jira/                  独立的 Jira CLI
devpulse-dist/           发行包（devpulse 与 Jira）
```

项目版本仅在根 `pom.xml` 的 `<revision>` 中定义；子模块的 `<parent><version>` 使用 `${revision}` 引用，不维护独立版本号。`devpulse-dependencies` 和 `devpulse-bom` 只生成 POM。工具输出和 AI 生成的命令应先审核；devpulse 不提供沙箱，也不内置 AI 调用。

## English

devpulse is a local DevOps toolbox built with Java 21 and Maven. `devpulse` is the single entry point: it reads `.devpulse.yaml` and runs tasks and dependencies through Bash. Jira is an independent tool with its own release. See [distribution and installation](devpulse-dist/README.md) for installing and upgrading either one.

### Quick start

From the repository root (use Git Bash on Windows):

```bash
bash build.sh --clean
bash run.sh --help
bash integration-test/jira/run-jira.sh --help
java -jar devpulse/target/devpulse.jar --help
```

`build.sh` builds every module by default, or use `bash build.sh pulse|jira|devpulse|dist` to build a selected target; `--clean` removes its previous output, while `dist` always cleans before packaging. `run.sh` builds and runs the unified entry point; `integration-test/jira/run-jira.sh` runs the built Jira JAR. Neither modifies the parent shell's environment. See [distribution and installation](devpulse-dist/README.md) for installation. Running the JARs requires Java 21 or later; `run` and `doctor` also require Bash.

### Positioning

devpulse targets task orchestration that is **local, container-free, native to Bash, and stateful**: dependencies are declared in YAML, the scheduler decides order and concurrency, long-running processes gate their dependents on readiness, and every execution leaves a queryable record. It is not a shell replacement, and it does not do container isolation or remote execution. For the boundary with shell scripts and with act, Earthly, Dagger, and Nx — plus the rules for deciding what to build next — see [positioning and competitive landscape](docs/design/positioning.md).

### Documentation

| Document | Contents |
|----------|----------|
| [Pulse commands](devpulse-pulse/README.md) | Task configuration, execution, review, status, and daemons |
| [Core guide](devpulse-core/README.md) | Java DAG Builder, embedded Bash workflows, and environment collection |
| [devpulse CLI guide](devpulse/README.md) | Unified entry point and command navigation |
| [Distribution and installation](devpulse-dist/README.md) | Local build installation, upgrades, and removal |
| [Jira guide](devpulse-tools/jira/README.md) | Connection configuration, issue operations, and Markdown boundaries |
| [Module architecture index](docs/architecture.md) | Architecture organized by Maven module |
| [Product positioning](docs/design/positioning.md) | Shell boundaries, competitors, and product design rules |
| [Product shape](docs/design/product-shape.md) | Server/client split, naming, YAML as data, and the order of work |
| [Server API design](docs/design/server-api.md) | Process model, auth, endpoints, error codes, and events |
| [Copilot repository instructions](.github/copilot-instructions.md) | Technology stack, coding, documentation, and build conventions |

### Maven modules

```text
devpulse-dependencies/   External dependency versions
devpulse-bom/            Internal artifact versions
devpulse-core/           DAG engine, shared Bash support, and environment collection
devpulse-storage/        Run state persistence (H2 + JDBI), schema migrations, and the reuse index
devpulse-execution/      Execution kernel: configuration, plan, fingerprints, and run orchestration
devpulse-cli/            Unified CLI: entry point, Pulse commands, server subcommand
devpulse-tools/          Independent tool aggregator
  jira/                  Standalone Jira CLI
devpulse-dist/           Release archives (devpulse and Jira)
```

Define the project version only in the root `pom.xml`'s `<revision>` property; child `<parent><version>` fields reference `${revision}` instead of maintaining separate versions. `devpulse-dependencies` and `devpulse-bom` produce POMs only. Review tool output and AI-generated commands before using them; devpulse is not a sandbox and does not include AI calls.
