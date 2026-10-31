# devpulse Java 实现基本思路与架构方案

本项目基于 Java 25+ 技术栈开发，使用 Maven 管理依赖、构建与打包，旨在构建一个轻量、高效、具备 AI 协同能力的本地 DevOps 智能调度工具箱。

## 一、核心技术选型（Dependencies）

为了保证工具箱的小巧和终端交互的极致体验，建议引入以下成熟的开源类库（统一通过 Maven 管理）：

- **命令行框架**：picocli（目前 Java 生态最强 CLI 框架，支持注解、彩色输出、自动生成帮助文档）。
- **配置解析器**：Typesafe Config（Maven 坐标 `com.typesafe:config`），仅支持 HOCON 格式的 `.devpulse.conf`，不支持 YAML。
- **日志/终端美化**：原生 Java 打印或集成 jansi，以支持丰富的终端色彩和动态进度条。

### Java 语言与平台特性使用原则

- 开发、构建与运行环境均要求 JDK 25 或更高版本；Maven Compiler Plugin 的 `release` 固定为 `25`，保证产物可在 Java 25 上运行。
- 优先使用 Java 25 中已正式发布的现代语言特性，在适合的场景使用 `record` 表达配置与环境事实，使用 `sealed` 类型、模式匹配和 `switch` 表达式描述与处理封闭的状态集合。
- 使用文本块维护多行 AI Prompt 模板，使用 try-with-resources 管理进程输出流等资源。
- 后续实现并发调度时，优先使用虚拟线程处理进程等待与日志读取等阻塞操作；外部进程的并发数量仍需独立限制。
- 默认不启用预览特性，避免用户运行时必须传入 `--enable-preview`；确需使用时，应明确记录原因及构建、运行要求。
- 以简洁和可维护性为前提采用新特性，不为使用新语法而增加不必要的抽象。

### Maven 构建与打包

- 使用 `pom.xml` 统一定义依赖和插件版本，并明确 UTF-8 源码编码。
- 使用 Maven Compiler Plugin 配置 `release` 为 `25`。
- 使用 Maven Shade Plugin 在 `package` 阶段生成包含运行依赖的可执行 JAR，并通过 `ManifestResourceTransformer` 将 `Main-Class` 设置为 `DevPulseCLI` 的全限定类名。
- 将构建产物名称固定为 `devpulse.jar`，执行 `mvn clean package` 后输出到 `target` 目录。
- 分发该 JAR 时，目标机器仍需安装 Java 25+。

```powershell
mvn clean package
java -jar target\devpulse.jar --help
```

### HOCON 配置加载

- 显式解析项目的 `.devpulse.conf`，通过 `withFallback` 合并内置 `reference.conf` 默认值，再调用 `resolve` 解析变量引用；不自动加载无关的 JVM 系统属性。
- 使用 Typesafe Config 的类型化读取 API 构造不可变 `record` 配置模型，不依赖 Jackson 映射。
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

**实现**：使用 picocli 的 `@Command` 注解定义主命令和子命令。

### 2. 资产自动盘点模块（`GatherCommand.java`）

**职责**：收集本地系统的客观环境事实，并动态组装、复制 AI 提示词（Prompt）到剪贴板。

**实现步骤**：

- **环境变量扫描**：读取 `System.getenv("PATH")`，利用 `File.exists()` 智能判断 `git`、`docker`、`kubectl` 等全局工具是否存在。
- **项目特征嗅探**：通过 `java.nio.file.Files.exists()` 检查当前工作目录下的关键指纹文件（如 `package.json`、`pom.xml`、`Dockerfile`、`.git`）。
- **提示词包装与剪贴板控制**：将上述客观事实填入预设好的 Markdown Prompt 模板。通过 Java 原生的 `java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()` 强制将生成的 Prompt 写入用户的系统剪贴板。

### 3. 图拓扑智能调度引擎（`PulseCommand.java`）

**职责**：读取用户存放的 `.devpulse.conf` 配置文件，智能计算依赖顺序，拉起本地系统进程执行。

**实现步骤**：

- **解析配置**：使用 Typesafe Config 加载 HOCON 配置、合并默认值并解析变量引用，再通过类型化读取构造 `record` 配置模型。
- **构建有向无环图（DAG）**：将 `tools` 列表中的每个工具视作一个节点（Vertex），将它们之间的 `needs` 依赖关系视作有向边（Edge）。
- **拓扑排序与循环依赖检测**：采用 Kahn 算法或 DFS 算法检查依赖图。如果发现循环依赖（例如 A 依赖 B，B 又依赖 A），在启动任何命令前报错；执行时仅将所有依赖均已成功的任务加入就绪队列，并在并发上限内调度。
- **底层进程控制器**：使用 `java.lang.ProcessBuilder` 与 `sh`/`cmd` 动态适配，拉起本地命令。将底层工具的错误流和标准流合并（`redirectErrorStream(true)`），通过缓冲流（`BufferedReader`）实时输出到用户终端。
- **并发上限**：默认最多同时运行 4 个任务；支持配置 `parallelism` 与 `pulse --jobs` 覆盖，优先级为命令行参数高于项目配置、高于默认值。取值必须为正整数，并在执行前校验；该限制不控制外部任务内部的并发。
- **失败处理**：任一任务失败后，停止启动新任务，等待所有已启动任务结束，最终返回非零退出码；不因该失败强制终止其他运行中任务。用户主动取消与任务超时的终止、进程清理规则另行定义。

## 三、开发落地路线图（Roadmap）

首版范围包含 `init`、`pulse` 的并发调度、`gather` 和 `status`，不将并发或 AI 协同流推迟到后续版本。以下阶段为内部开发顺序，而不是对外版本的功能拆分。

### 阶段一：建立脚手架与命令行骨架（MVP）

- 配置好 Maven 依赖、Java 25 编译目标与可执行 JAR 打包插件，写出带有 `@Command` 的 Java 类。
- 确保执行 `mvn clean package` 后，在终端运行 `java -jar target\devpulse.jar --help` 能打印出规范的帮助说明。

### 阶段二：攻克 gather 模块（打通 AI 协同流）

- 编写本地文件和 `PATH` 环境变量的扫描逻辑。
- 编写 AWT 剪贴板写入代码，确保敲击 `gather` 后，用户能直接在微信、浏览器或任何 AI 窗口中按 `Ctrl+V` 粘贴出完美的 Prompt。

### 阶段三：攻克 pulse 模块（打通调度流）

- 引入 Typesafe Config 解析 HOCON 配置文件，完成默认值合并与执行前校验。
- 手写基于 Kahn 的拓扑排序函数，确保能够处理复杂的相互依赖。
- 实现依赖就绪任务的并发调度，落实默认 4 个任务的并发上限，以及 `parallelism`、`--jobs` 的覆盖规则。
- 完善 `ProcessBuilder` 的异常处理，落实停止新任务、等待运行中任务结束的失败策略，不会引起系统崩溃。
- 完成 `init` 默认配置生成与 `status` 本地工具健康状态展示，形成首版完整命令集合。
