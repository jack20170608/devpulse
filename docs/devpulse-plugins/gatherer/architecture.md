# Gatherer 架构设计 / Gatherer architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../../architecture.md) | [使用说明](../../../devpulse-plugins/gatherer/README.md)

### 模块职责

`devpulse-plugins/gatherer` 是可复用的环境检测库，承接从 core 移植的 `io.github.devpulse.gatherer.EnvironmentGatherer`。Gather CLI 与 Pulse 的 `status --tools` 显式依赖它；它不是另一套 CLI，不引入插件注册或动态加载。

### 依赖与采集

通过 Maven 依赖 `devpulse-core`，只使用共享 `BashShell` 查找并验证 Bash、执行固定工具盘点脚本。core 不依赖 gatherer；Gather CLI 不依赖 Pulse，两个工具共享检测库。

`gather()` 校验并规范化目录，通过 `command -v` 获取固定工具位置，拒绝不完整盘点，并检查根目录项目标志。返回 `Facts` / `Tool` record，列表使用防御性复制。工具清单、项目标志、异常和中断行为保持不变，详细契约见使用说明。

### 功能边界与后续调整

不读取项目文件、不递归扫描、不采集环境变量值、不运行工具版本或服务健康检查。提示词、编码提示、剪贴板和终端输出由调用方管理。本次不扩展检测能力；后续调整集中在 gatherer，不将环境检测重新放回 DAG 或 Bash 调度核心。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugins/gatherer/README.md)

### Module responsibility

`devpulse-plugins/gatherer` is a reusable environment detection library hosting `io.github.devpulse.gatherer.EnvironmentGatherer`, relocated from core. The Gather CLI and Pulse's `status --tools` explicitly depend on it. It is not another CLI and introduces no plugin registration or dynamic loading.

### Dependencies and collection

Its Maven dependency on `devpulse-core` supplies only the shared `BashShell` used for Bash discovery/verification and the fixed inventory script. Core does not depend on gatherer; the Gather CLI does not depend on Pulse, and both tools share the detection library.

`gather()` validates and normalizes the directory, obtains fixed tool locations with `command -v`, rejects incomplete inventories, and checks root-level project markers. It returns `Facts` / `Tool` records with defensive list copies. Tool lists, markers, exceptions, and interruption behavior remain unchanged; the usage guide documents the contract.

### Limits and future changes

No project file reads, recursive scans, environment value collection, tool version checks, or service health checks. Callers own prompts, encoding warnings, clipboard access, and terminal output. This change adds no detection capabilities; future changes stay in gatherer rather than returning detection logic to the DAG or Bash scheduling core.
