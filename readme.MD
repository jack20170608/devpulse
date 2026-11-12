# devpulse

[中文](#中文) | [English](#english)

## 中文

devpulse 是基于 Java 25 和 Maven 的本地 DevOps 工具箱。Pulse 使用 `.devpulse.yaml` 中的 YAML 配置描述用户自定义任务和依赖，通过 Bash 执行；Jira 独立运行。两者可以分别安装。旧 HOCON 任务配置的迁移方式见 [Pulse 使用说明](devpulse-plugins/pulse/README.md)。

### 快速开始

在仓库根目录运行（Windows 使用 Git Bash）：

```bash
bash build.sh --clean
bash run.sh --help
bash integration-test/run-jira.sh --help
java -jar devpulse-plugins/pulse/target/devpulse-pulse.jar --help
```

`build.sh` 默认构建全部模块，也支持 `bash build.sh pulse|gather|gatherer|jira|depmap|cli|dist` 按需构建，`--clean` 可清理所选模块的旧产物。`run.sh` 保留组合式 CLI 兼容入口；`integration-test/run-jira.sh` 只构建和运行 Jira。两个启动脚本都复用 `build.sh`，不会修改父终端的环境变量。[集成测试](integration-test/README.md) 使用 `bash integration-test/run.sh jira`，默认离线，真实 Jira 写入需显式启用。可选安装与发行包见 [发行与安装](devpulse-dist/README.md)。运行 JAR 需要 Java 25 或更高版本；实际执行 `pulse`、`gather` 和 `status --tools` 还需要 Bash。

### 文档导航

| 文档 | 内容 |
|------|------|
| [Pulse 使用说明](devpulse-plugins/pulse/README.md) | 任务配置、执行、审阅、状态与长驻进程 |
| [Gather 使用说明](devpulse-plugins/gather/README.md) | 环境盘点与 AI 提示词 |
| [Gatherer 使用说明](devpulse-plugins/gatherer/README.md) | 可复用环境检测库 |
| [Core 使用说明](devpulse-core/README.md) | Java DAG Builder 与 Bash 工作流嵌入 |
| [兼容 CLI 使用说明](devpulse-cli/README.md) | 组合式入口与命令导航 |
| [发行与安装](devpulse-dist/README.md) | 本地构建安装、升级与卸载 |
| [Jira 使用说明](devpulse-plugins/jira/README.md) | 连接配置、Issue 操作与 Markdown 边界 |
| [Depmap 使用说明](devpulse-plugins/depmap/README.md) | 项目及插件依赖树与 JSON 导出 |
| [模块架构导航](docs/architecture.md) | 按 Maven 模块组织的架构设计 |
| [Copilot 仓库指令](.github/copilot-instructions.md) | 技术栈、编码、文档及构建规范 |

### Maven 模块

```text
devpulse-dependencies/   外部依赖版本
devpulse-bom/            内部产物版本
devpulse-core/           DAG 执行引擎与 Bash 共享库
devpulse-plugins/        插件聚合模块
  gatherer/              环境检测共享库
  pulse/                 独立的 Pulse CLI
  gather/                独立的 Gather CLI
  jira/                  独立的 Jira CLI
  depmap/                独立的 Maven 依赖盘点 CLI
devpulse-cli/            组合式 CLI 兼容入口
devpulse-dist/           可选工具发行包
```

项目版本仅在根 `pom.xml` 的 `<revision>` 中定义；子模块的 `<parent><version>` 使用 `${revision}` 引用，不维护独立版本号。`devpulse-dependencies` 和 `devpulse-bom` 只生成 POM。工具输出和 AI 生成的命令应先审核；devpulse 不提供沙箱，也不内置 AI 调用。

## English

devpulse is a local DevOps toolbox built with Java 25 and Maven. Pulse uses YAML in `.devpulse.yaml` to describe user-defined tasks and dependencies and runs them through Bash. Jira runs independently; the tools can be installed separately. See the [Pulse guide](devpulse-plugins/pulse/README.md) for migrating legacy HOCON task configurations.

### Quick start

From the repository root (use Git Bash on Windows):

```bash
bash build.sh --clean
bash run.sh --help
bash integration-test/run-jira.sh --help
java -jar devpulse-plugins/pulse/target/devpulse-pulse.jar --help
```

`build.sh` builds every module by default, or use `bash build.sh pulse|gather|gatherer|jira|depmap|cli|dist` to build a selected target; `--clean` removes its previous output. `run.sh` preserves the combined CLI for compatibility, while `integration-test/run-jira.sh` builds and launches only Jira. Both launchers use `build.sh` and do not change the parent shell's environment. Run [integration tests](integration-test/README.md) with `bash integration-test/run.sh jira`; they are offline by default and require explicit authorization for live Jira writes. See [distribution and installation](devpulse-dist/README.md) for selective installation. Running the JARs requires Java 25 or later; actual `pulse` execution, `gather`, and `status --tools` also require Bash.

### Documentation

| Document | Contents |
|----------|----------|
| [Pulse guide](devpulse-plugins/pulse/README.md) | Task configuration, execution, review, status, and daemons |
| [Gather guide](devpulse-plugins/gather/README.md) | Environment inventory and AI prompts |
| [Gatherer guide](devpulse-plugins/gatherer/README.md) | Reusable environment detection library |
| [Core guide](devpulse-core/README.md) | Java DAG Builder and embedded Bash workflows |
| [Compatibility CLI guide](devpulse-cli/README.md) | Combined entry point and command navigation |
| [Distribution and installation](devpulse-dist/README.md) | Local build installation, upgrades, and removal |
| [Jira guide](devpulse-plugins/jira/README.md) | Connection configuration, issue operations, and Markdown boundaries |
| [Depmap guide](devpulse-plugins/depmap/README.md) | Project/plugin dependency trees and JSON export |
| [Module architecture index](docs/architecture.md) | Architecture organized by Maven module |
| [Copilot repository instructions](.github/copilot-instructions.md) | Technology stack, coding, documentation, and build conventions |

### Maven modules

```text
devpulse-dependencies/   External dependency versions
devpulse-bom/            Internal artifact versions
devpulse-core/           DAG execution engine and shared Bash library
devpulse-plugins/        Plugin aggregator
  gatherer/              Shared environment detection library
  pulse/                 Standalone Pulse CLI
  gather/                Standalone Gather CLI
  jira/                  Standalone Jira CLI
  depmap/                Standalone Maven dependency inventory CLI
devpulse-cli/            Combined compatibility CLI
devpulse-dist/           Selective tool archives
```

Define the project version only in the root `pom.xml`'s `<revision>` property; child `<parent><version>` fields reference `${revision}` instead of maintaining separate versions. `devpulse-dependencies` and `devpulse-bom` produce POMs only. Review tool output and AI-generated commands before using them; devpulse is not a sandbox and does not include AI calls.
