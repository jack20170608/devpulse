# devpulse-tools 架构 / devpulse-tools architecture

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

## 中文

`devpulse-tools` 是 `packaging=pom` 的聚合模块，收纳与本产品核心无关、可独立发展的工具。当前只有 [Jira](jira/architecture.md)。

这些工具不依赖 `devpulse-core`、`devpulse-storage` 或 `devpulse-cli`，也不被它们依赖；聚合只决定 reactor 组织。`devpulse-plugins` 这一名称留给将来的执行器插件（如 `executor-bash`），与本模块无关。

## English

`devpulse-tools` is a `packaging=pom` aggregator for tools that are unrelated to the product core and can evolve on their own. Today it holds only [Jira](jira/architecture.md).

These tools depend on neither `devpulse-core`, `devpulse-storage` nor `devpulse-cli`, and nothing depends on them. Aggregation only organizes the reactor. The name `devpulse-plugins` is reserved for executor plugins such as `executor-bash`.
