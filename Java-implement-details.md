# devpulse Java 实现基本思路与架构方案

本项目旨在构建一个轻量、高效、具备 AI 协同能力的本地 DevOps 智能调度工具箱。

技术栈、语言使用原则、构建打包、资源管理和测试约束统一维护在 [Copilot 仓库指令](.github/copilot-instructions.md) 中；本文描述功能行为、核心架构与开发路线图。

## 一、配置格式与加载

- 仅支持 HOCON 格式的 `.devpulse.conf`，不支持 YAML。
- 显式解析项目的 `.devpulse.conf`，通过 `withFallback` 合并内置 `reference.conf` 默认值，再调用 `resolve` 解析变量引用；不自动加载无关的 JVM 系统属性。
- 使用 Typesafe Config 的类型化读取 API 构造配置模型。
- `include` 仅允许本地文件或 classpath 资源，不允许远程 URL；实现时需限制包含资源的加载行为，而不是仅约定用户不写远程地址。
- 配置错误应报告文件来源及可用的行号信息；字段类型、重复任务名、未知依赖和循环依赖均需在执行任何命令之前校验。
- `gather` 生成的 Prompt 与 `init` 生成的默认配置统一使用 HOCON 示例。

## 二、核心架构设计（Architecture）

整个 devpulse 系统由一个主入口和三大核心模块组成，职责清晰，方便后续解耦和扩展。它的文字流向结构如下：

```text
用户终端输入（CLI Input） -> devpulse 主入口类 -> 分发至对应子模块
```

### 1. 主入口类（`DevPulseCLI.java`）

**职责**：负责接收终端传入的参数（如 `gather`、`pulse`），进行基本的全局校验。

### 2. 资产自动盘点模块（`GatherCommand.java`）

**职责**：收集本地系统的客观环境事实，并动态组装、复制 AI 提示词（Prompt）到剪贴板。

**实现步骤**：

- **环境变量扫描**：读取 `System.getenv("PATH")`，利用 `File.exists()` 智能判断 `git`、`docker`、`kubectl` 等全局工具是否存在。
- **项目特征嗅探**：通过 `java.nio.file.Files.exists()` 检查当前工作目录下的关键指纹文件（如 `package.json`、`pom.xml`、`Dockerfile`、`.git`）。
- **提示词包装与剪贴板控制**：将上述客观事实填入预设好的 Markdown Prompt 模板。通过 Java 原生的 `java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()` 强制将生成的 Prompt 写入用户的系统剪贴板。

### 3. 图拓扑智能调度引擎（`PulseCommand.java`）

**职责**：读取用户存放的 `.devpulse.conf` 配置文件，智能计算依赖顺序，拉起本地系统进程执行。

**实现步骤**：

- **解析配置**：按配置加载规则读取 HOCON 配置，构造配置模型。
- **构建有向无环图（DAG）**：将 `tools` 列表中的每个工具视作一个节点（Vertex），将它们之间的 `needs` 依赖关系视作有向边（Edge）。
- **拓扑排序与循环依赖检测**：采用 Kahn 算法或 DFS 算法检查依赖图。如果发现循环依赖（例如 A 依赖 B，B 又依赖 A），在启动任何命令前报错；执行时仅将所有依赖均已成功的任务加入就绪队列，并在并发上限内调度。
- **底层进程控制器**：使用 `java.lang.ProcessBuilder` 与 `sh`/`cmd` 动态适配，拉起本地命令。将底层工具的错误流和标准流合并（`redirectErrorStream(true)`），通过缓冲流（`BufferedReader`）实时输出到用户终端。
- **并发上限**：默认最多同时运行 4 个任务；支持配置 `parallelism` 与 `pulse --jobs` 覆盖，优先级为命令行参数高于项目配置、高于默认值。取值必须为正整数，并在执行前校验；该限制不控制外部任务内部的并发。
- **失败处理**：任一任务失败后，停止启动新任务，等待所有已启动任务结束，最终返回非零退出码；不因该失败强制终止其他运行中任务。用户主动取消与任务超时的终止、进程清理规则另行定义。

## 三、开发落地路线图（Roadmap）

首版范围包含 `init`、`pulse` 的并发调度、`gather` 和 `status`，不将并发或 AI 协同流推迟到后续版本。以下阶段为内部开发顺序，而不是对外版本的功能拆分。

### 阶段一：建立脚手架与命令行骨架（MVP）

- 按 Copilot 仓库指令建立构建与命令行骨架。
- 确保打包后的可执行 JAR 能通过 `--help` 打印出规范的帮助说明。

### 阶段二：攻克 gather 模块（打通 AI 协同流）

- 编写本地文件和 `PATH` 环境变量的扫描逻辑。
- 编写 AWT 剪贴板写入代码，确保敲击 `gather` 后，用户能直接在微信、浏览器或任何 AI 窗口中按 `Ctrl+V` 粘贴出完美的 Prompt。

### 阶段三：攻克 pulse 模块（打通调度流）

- 引入 Typesafe Config 解析 HOCON 配置文件，完成默认值合并与执行前校验。
- 手写基于 Kahn 的拓扑排序函数，确保能够处理复杂的相互依赖。
- 实现依赖就绪任务的并发调度，落实默认 4 个任务的并发上限，以及 `parallelism`、`--jobs` 的覆盖规则。
- 完善 `ProcessBuilder` 的异常处理，落实停止新任务、等待运行中任务结束的失败策略，不会引起系统崩溃。
- 完成 `init` 默认配置生成与 `status` 本地工具健康状态展示，形成首版完整命令集合。
