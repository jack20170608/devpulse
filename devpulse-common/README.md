# devpulse-common 使用说明 / devpulse-common usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD) | [架构设计](../docs/devpulse-common/architecture.md)

`devpulse-common` 存放各模块共享的通用代码，包名为 `io.github.devpulse.common`。它不依赖任何工具模块，也不注册命令。

需要使用时，在消费模块中声明 `io.github.devpulse:devpulse-common` 依赖（版本由 `devpulse-bom` 管理），并通过根 reactor 的 `--also-make` 一起构建。

## English

[Project home](../readme.MD) | [Architecture](../docs/devpulse-common/architecture.md)

`devpulse-common` holds code shared across modules, under the `io.github.devpulse.common` package. It depends on no tool module and registers no commands.

To use it, declare the `io.github.devpulse:devpulse-common` dependency in the consuming module (version managed by `devpulse-bom`) and build through the root reactor with `--also-make`.
