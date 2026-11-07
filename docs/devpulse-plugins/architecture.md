# 插件聚合模块架构设计 / Plugin aggregator architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [模块 POM](../../devpulse-plugins/pom.xml)

### 聚合而非动态插件

`devpulse-plugins` 是 `packaging=pom` 的 Maven 聚合模块，列出 `pulse`、`gather`、`jira` 和 `depmap`，通过本地父路径继承 `devpulse-dependencies`。聚合关系只决定 reactor 的模块组织，不表示工具之间存在运行时依赖。

每个子模块定义自己的 picocli 入口并通过 Shade 生成独立可执行 JAR；所需共享库通过显式 Maven 依赖引入。不存在插件注册表、运行时扫描或动态加载。兼容 CLI 的组合以及发行归档由各自模块负责。

### 子模块文档

各工具的使用说明位于源码模块根目录 `README.md`；架构分别位于本目录的 [Pulse](pulse/architecture.md)、[Gather](gather/architecture.md)、[Jira](jira/architecture.md) 和 [Depmap](depmap/architecture.md) 子目录，不在聚合模块重复维护功能正文。

## English

[Architecture index](../architecture.md) | [Module POM](../../devpulse-plugins/pom.xml)

### Aggregation, not dynamic plugins

`devpulse-plugins` is a `packaging=pom` Maven aggregator listing `pulse`, `gather`, `jira`, and `depmap` and inheriting `devpulse-dependencies` through a local parent path. Aggregation organizes reactor modules rather than establishing runtime dependencies between tools.

Each child defines its own picocli entry point and uses Shade to produce a standalone executable JAR. Required shared libraries are explicit Maven dependencies. There is no plugin registry, runtime scanning, or dynamic loading. The compatibility CLI and distribution modules separately own command composition and release archives.

### Child-module documentation

Each tool's usage guide lives in its source module root as `README.md`. Architecture lives in the [Pulse](pulse/architecture.md), [Gather](gather/architecture.md), [Jira](jira/architecture.md), and [Depmap](depmap/architecture.md) subdirectories here, without duplicating feature documentation in the aggregator.
