# 模块架构导航 / Module architecture index

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

原集中式架构正文已按 Maven 模块拆分，本文仅保留导航。使用说明位于各模块根目录的 `README.md`；构建与 JAR 示例均以仓库根目录为工作目录。

### 模块架构

| 模块 | 职责 |
|------|------|
| [`devpulse-core`](devpulse-core/architecture.md) | Java DAG、Bash 计划与运行时、历史及长驻进程 |
| [`devpulse-plugins/pulse`](devpulse-plugins/pulse/architecture.md) | YAML 配置、任务命令与静态审阅 |
| [`devpulse-plugins/gather`](devpulse-plugins/gather/architecture.md) | 环境提示词与剪贴板输出 |
| [`devpulse-plugins/jira`](devpulse-plugins/jira/architecture.md) | Jira REST、连接配置与内容转换 |
| [`devpulse-plugins/depmap`](devpulse-plugins/depmap/architecture.md) | 内嵌 Maven 与依赖报告 |
| [`devpulse-cli`](devpulse-cli/architecture.md) | 组合式兼容命令分发 |
| [`devpulse-dist`](devpulse-dist/architecture.md) | 工具归档、校验与安装 |
| [`devpulse-dependencies`](devpulse-dependencies/architecture.md) | 外部依赖与构建版本继承 |
| [`devpulse-bom`](devpulse-bom/architecture.md) | 内部可消费模块版本 |
| [`devpulse-plugins`](devpulse-plugins/architecture.md) | 独立工具的 Maven 聚合 |

### 依赖与文档边界

独立工具不互相依赖；Pulse 和 Gather 复用 core，Jira 和 Depmap 独立运行。兼容 CLI 组合 Pulse/Gather，发行模块仅打包 Pulse/Jira；core 不注册命令或动态加载工具。Java DAG 与 Bash 工作流是两套独立 API。

技术栈及仓库约束见 [Copilot 仓库指令](../.github/copilot-instructions.md)。原使用说明路径保留双语迁移页，不重复维护正文。

## English

[Project home](../readme.MD)

The former monolithic architecture document has been split by Maven module; this page retains navigation only. Usage guides live in each module root as `README.md`; build and JAR examples use the repository root as their working directory.

### Module architecture

| Module | Responsibility |
|--------|----------------|
| [`devpulse-core`](devpulse-core/architecture.md) | Java DAG, Bash planning/runtime, history, and daemons |
| [`devpulse-plugins/pulse`](devpulse-plugins/pulse/architecture.md) | YAML configuration, workflow commands, and static review |
| [`devpulse-plugins/gather`](devpulse-plugins/gather/architecture.md) | Environment prompts and clipboard output |
| [`devpulse-plugins/jira`](devpulse-plugins/jira/architecture.md) | Jira REST, connection configuration, and content conversion |
| [`devpulse-plugins/depmap`](devpulse-plugins/depmap/architecture.md) | Embedded Maven and dependency reports |
| [`devpulse-cli`](devpulse-cli/architecture.md) | Combined compatibility command dispatch |
| [`devpulse-dist`](devpulse-dist/architecture.md) | Tool archives, checksums, and installation |
| [`devpulse-dependencies`](devpulse-dependencies/architecture.md) | External dependencies and inherited build versions |
| [`devpulse-bom`](devpulse-bom/architecture.md) | Consumable internal module versions |
| [`devpulse-plugins`](devpulse-plugins/architecture.md) | Maven aggregation of independent tools |

### Dependency and documentation boundaries

Standalone tools do not depend on each other. Pulse and Gather reuse core; Jira and Depmap run independently. The compatibility CLI combines Pulse/Gather; distribution packages only Pulse/Jira. Core neither registers commands nor dynamically loads tools. Java DAG and Bash workflows are independent APIs.

See the [Copilot repository instructions](../.github/copilot-instructions.md) for the technology stack and repository constraints. Former usage paths retain bilingual migration pages rather than duplicate content.
