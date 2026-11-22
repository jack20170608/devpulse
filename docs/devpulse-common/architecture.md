# devpulse-common 架构设计 / devpulse-common architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [模块 POM](../../devpulse-common/pom.xml) | [使用说明](../../devpulse-common/README.md)

### 职责与边界

`devpulse-common` 是普通 JAR 模块，存放跨模块共享的通用代码（包 `io.github.devpulse.common`）。它是依赖图的底层：不依赖 `devpulse-core`、插件或 CLI，也不注册命令、不动态加载工具。

### 父 POM 与版本

通过本地相对路径继承 `devpulse-dependencies`，版本由根 `<revision>` 决定，并登记在 `devpulse-bom` 中。消费模块需通过根 reactor 的 `--also-make` 一起构建。

## English

[Architecture index](../architecture.md) | [Module POM](../../devpulse-common/pom.xml) | [Usage](../../devpulse-common/README.md)

### Responsibilities and boundaries

`devpulse-common` is a plain JAR module holding code shared across modules (package `io.github.devpulse.common`). It sits at the bottom of the dependency graph: it does not depend on `devpulse-core`, plugins, or the CLI, and it neither registers commands nor dynamically loads tools.

### Parent POM and version

It inherits `devpulse-dependencies` via a local relative path, takes its version from the root `<revision>`, and is listed in `devpulse-bom`. Consuming modules must be built through the root reactor with `--also-make`.
