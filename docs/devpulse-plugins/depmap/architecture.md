# Depmap 架构设计 / Depmap architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../../architecture.md) | [使用说明](../../../devpulse-plugins/depmap/README.md)

### 模块与解析边界

**Depmap 依赖盘点**：`devpulse-plugins/depmap` 是独立可执行工具，不依赖其他工具或共享库，不加入组合入口和发行包。使用固定版本的内嵌 Maven 3.9.x 构建有效模型与 reactor 工作区，并分别收集项目依赖树、构建和报告插件依赖树；不启动 `mvn`，不执行构建任务或加载目标项目扩展。终端输出和 JSON 共享不可变报告模型，部分失败保留已有结果、明确标记不完整并返回非零退出码。外部依赖版本集中在 `devpulse-dependencies`，Shade 合并服务、Plexus 组件和 Sisu 索引。参数、输出结构与支持边界见 [Depmap 依赖盘点](../../../devpulse-plugins/depmap/README.md)。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugins/depmap/README.md)

### Module and resolution boundaries

**Depmap dependency inventory**: `devpulse-plugins/depmap` is a standalone executable, with no dependency on other tools or the shared library, and is excluded from the combined entry point and distribution archives. It uses pinned embedded Maven 3.9.x to build effective models and a reactor workspace, separately collecting project, build plugin, and report plugin dependency trees. It does not start `mvn`, execute build tasks, or load target project extensions. Terminal and JSON output share an immutable report model; partial failures retain collected results, explicitly mark them incomplete, and return a nonzero exit code. External versions are centralized in `devpulse-dependencies`; Shade merges services, Plexus components, and Sisu indexes. See [Depmap dependency inventory](../../../devpulse-plugins/depmap/README.md) for options, output structure, and supported boundaries.
