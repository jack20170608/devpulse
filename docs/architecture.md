# 模块架构导航 / Module architecture index

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

原集中式架构正文已按 Maven 模块拆分，本文仅保留导航。使用说明位于各模块根目录的 `README.md`；构建与 JAR 示例均以仓库根目录为工作目录。

### 模块架构

| 模块 | 职责 |
|------|------|
| [`devpulse-common`](devpulse-common/architecture.md) | 跨模块共享通用代码 |
| [`devpulse-core`](devpulse-core/architecture.md) | 纯 DAG 调度：图校验、就绪队列、虚拟线程调度与协作取消（零依赖） |
| [`devpulse-plugin`](devpulse-plugin/architecture.md) | 执行插件聚合：每个插件独立成模块，只依赖 `devpulse-core` |
| [`devpulse-plugin/bash`](devpulse-plugin/bash/architecture.md) | Bash 执行插件：进程执行、Daemon 管理、环境采集与 `TaskHistory` 接缝 |
| [`devpulse-storage`](design/relational-storage.md) | 运行状态的关系型存储、schema 迁移与复用索引 |
| [`devpulse-execution`](devpulse-execution/architecture.md) | 执行内核：配置解析、计划、指纹、运行编排与运行记录 |
| [`devpulse-server`](devpulse-server/architecture.md) | 本地服务端：回环 HTTP、token 认证与健康/环境端点 |
| [`devpulse-cli`](devpulse-cli/architecture.md) | 统一 CLI：入口装配、Pulse 命令与呈现 |
| [`devpulse-tools/jira`](devpulse-tools/jira/architecture.md) | Jira REST、连接配置与内容转换 |
| [`devpulse-dist`](devpulse-dist/architecture.md) | 工具归档、校验与安装 |
| [`devpulse-dependencies`](devpulse-dependencies/architecture.md) | 外部依赖与构建版本继承 |
| [`devpulse-bom`](devpulse-bom/architecture.md) | 内部可消费模块版本 |
| [`devpulse-tools`](devpulse-tools/architecture.md) | 独立工具的 Maven 聚合（当前仅 Jira） |

### 专题文档

| 文档 | 内容 |
|------|------|
| [GraalVM 原生镜像踩坑清单](research/native-image-pitfalls.md) | 打包原生可执行文件时的元数据、资源、构建期初始化与平台差异 |
| [数据库层资料汇编](research/database-layer-research.md) | 持久层设计与实现的外部资料索引 |
| [core + plugin 演进预研](research/core-plugin-evolution.md) | 何时引入执行器 SPI、三条路线对比与反模式清单 |

### 依赖与文档边界

独立工具不互相依赖；Pulse 直接复用 core 的环境采集与 Bash 能力，其工作流也依赖 core。Jira 独立运行。CLI 模块把 Pulse 命令与服务端子命令装配成一个可执行文件，发行模块打包 CLI 与 Jira；core 不注册命令或动态加载工具。Java 任务与 Bash 工作流共享 DAG 调度，Bash Adapter 负责进程、日志和历史；Daemon 支持就绪及健康监测，由用户管理停止。

技术栈及仓库约束见 [Copilot 仓库指令](../.github/copilot-instructions.md)。原使用说明路径保留双语迁移页，不重复维护正文。

## English

[Project home](../readme.MD)

The former monolithic architecture document has been split by Maven module; this page retains navigation only. Usage guides live in each module root as `README.md`; build and JAR examples use the repository root as their working directory.

### Module architecture

| Module | Responsibility |
|--------|----------------|
| [`devpulse-common`](devpulse-common/architecture.md) | Shared common code |
| [`devpulse-core`](devpulse-core/architecture.md) | Pure DAG scheduling: graph validation, ready queue, virtual-thread dispatch, cooperative cancellation (zero dependencies) |
| [`devpulse-plugin`](devpulse-plugin/architecture.md) | Execution plugin aggregation: each plugin is its own module and depends only on `devpulse-core` |
| [`devpulse-plugin/bash`](devpulse-plugin/bash/architecture.md) | Bash execution plugin: process execution, daemons, environment collection, and the `TaskHistory` seam |
| [`devpulse-storage`](design/relational-storage.md) | Relational run state, schema migrations, and the reuse index |
| [`devpulse-execution`](devpulse-execution/architecture.md) | Execution kernel: configuration, plan, fingerprints, run orchestration, and run records |
| [`devpulse-server`](devpulse-server/architecture.md) | Local server: loopback HTTP, token auth, health and environment endpoints |
| [`devpulse-cli`](devpulse-cli/architecture.md) | Unified CLI: entry assembly, Pulse commands, and presentation |
| [`devpulse-tools/jira`](devpulse-tools/jira/architecture.md) | Jira REST, connection configuration, and content conversion |
| [`devpulse-dist`](devpulse-dist/architecture.md) | Tool archives, checksums, and installation |
| [`devpulse-dependencies`](devpulse-dependencies/architecture.md) | External dependencies and inherited build versions |
| [`devpulse-bom`](devpulse-bom/architecture.md) | Consumable internal module versions |
| [`devpulse-tools`](devpulse-tools/architecture.md) | Maven aggregation of independent tools (Jira today) |

### Topic guides

| Document | Subject |
|----------|---------|
| [GraalVM native image pitfalls](research/native-image-pitfalls.md) | Metadata, resources, build-time initialization, and platform differences when packaging a native executable |
| [Database layer resources](research/database-layer-research.md) | External reading list for persistence design and implementation |
| [Evolving core into a plugin host](research/core-plugin-evolution.md) | When to introduce an executor SPI, three options compared, and anti-patterns |

### Dependency and documentation boundaries

Standalone tools do not depend on each other. Pulse directly reuses core's environment collection and Bash support, and its workflows also depend on core. Jira runs independently. The compatibility CLI combines Pulse commands; distribution packages only Pulse/Jira. Core neither registers commands nor dynamically loads tools. Java tasks and Bash workflows share DAG scheduling; the Bash Adapter handles processes, logs, and history. Daemons support readiness/health monitoring and user-managed stopping.

See the [Copilot repository instructions](../.github/copilot-instructions.md) for the technology stack and repository constraints. Former usage paths retain bilingual migration pages rather than duplicate content.
