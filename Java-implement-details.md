# devpulse Java 实现基本思路与架构方案（完整纯文本版）

本项目基于 Java 11+ 技术栈开发，旨在构建一个轻量、高效、具备 AI 协同能力的本地 DevOps 智能调度工具箱。

## 一、 核心技术选型 (Dependencies)

为了保证工具箱的小巧和终端交互的极致体验，建议引入以下成熟的开源类库（可通过 Maven 或 Gradle 管理）：
• 命令行框架: picocli (目前 Java 生态最强 CLI 框架，支持注解、彩色输出、自动生成帮助文档)。
• YAML 解析器: jackson-dataformat-yaml (用于在 Java 对象与 .devpulse.yaml 配置文件之间进行无缝反序列化)。
• 日志/终端美化: 原生 Java 打印或集成 jansi 以支持丰富的终端色彩和动态进度条。

## 二、 核心架构设计 (Architecture)

整个 devpulse 系统由一个主入口和三大核心模块组成，职责清晰，方便后续解耦和扩展。它的文字流向结构如下：
用户终端输入 (CLI Input) -> devpulse 主入口类 -> 分发至对应子模块
1. 主入口类 (DevPulseCLI.java)
   • 职责: 负责接收终端传入的参数（如 gather, pulse），进行基本的全局校验。
   • 实现: 使用 picocli 的 @Command 注解定义主命令和子命令。
2. 资产自动盘点模块 (GatherCommand.java)
   • 职责: 收集本地系统的客观环境事实，并动态组装、复制 AI 提示词（Prompt）到剪贴板。
   • 实现步骤:
   • 环境变量扫描: 读取 System.getenv("PATH")，利用 File.exists() 智能判断 git, docker, kubectl 等全局工具是否存在。
   • 项目特征嗅探: 通过 java.nio.file.Files.exists() 检查当前工作目录下的关键指纹文件（如 package.json, pom.xml, Dockerfile, .git）。
   • 提示词包装与剪贴板控制: 将上述客观事实填入预设好的 Markdown Prompt 模板。通过 Java 原生的 java.awt.Toolkit.getDefaultToolkit().getSystemClipboard() 强制将生成的 Prompt 写入用户的系统剪贴板。
3. 图拓扑智能调度引擎 (PulseCommand.java)
   • 职责: 读取用户存放的 .devpulse.yaml 配置文件，智能计算依赖顺序，拉起本地系统进程执行。
   • 实现步骤:
   • 解析配置: 使用 Jackson YAML Mapper 将配置文件读取为 Java Map 或特定的 POJO 实体。
   • 构建有向无环图 (DAG): 将 tools 列表中的每个工具视作一个节点（Vertex），将它们之间的 needs 依赖关系视作有向边（Edge）。
   • 拓扑排序与循环依赖检测: 采用 Kahn 算法 或 DFS 算法 对图进行拓扑排序。如果算法执行中发现排序后的节点数少于总节点数，说明用户定义的依赖存在闭环（例如 A 依赖 B，B 又依赖 A），此时智能拦截并报错提示；否则，输出最优的线性执行队列。
   • 底层进程控制器: 遍历执行队列，使用 java.lang.ProcessBuilder 与 sh/cmd 动态适配，拉起本地命令。将底层工具的错误流和标准流合并（redirectErrorStream(true)），通过缓冲流（BufferedReader）实时输出到用户终端。

## 三、 开发落地路线图 (Roadmap)

为了不延误开发时间，建议采取渐进式迭代，分为三个阶段逐步攻克：
• 阶段一：建立脚手架与命令行骨架 (MVP)
• 配置好 Maven 依赖，写出带有 @Command 的 Java 类。
• 确保在终端执行 java -jar devpulse.jar --help 能打印出规范的帮助说明。
• 阶段二：攻克 gather 模块（打通 AI 协同流）
• 编写本地文件和 PATH 环境变量的扫描逻辑。
• 编写 AWT 剪贴板写入代码，确保敲击 gather 后，用户能直接在微信、浏览器或任何 AI 窗口中按 Ctrl+V 粘贴出完美的 Prompt。
• 阶段三：攻克 pulse 模块（打通调度流）
• 引入 Jackson 解析 YAML 配置文件。
• 手写基于 Kahn 的拓扑排序函数，确保能够处理复杂的相互依赖。
• 完善 ProcessBuilder 的异常处理，确保单个工具执行失败时能够安全中断，不会引起系统崩溃。
