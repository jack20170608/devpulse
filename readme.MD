# devpulse

**`devpulse`** 是一个专为开发者和运维工程师打造的**本地 DevOps 智能调度工具箱**。它能够将你本地零散的各种脚本、CLI 工具和自动化流完美集成，并通过智能调度引擎，像心脏跳动一样精准、高效地串联并驱动它们。

无需配置复杂的远程 CI/CD 平台，在本地终端，一切尽在掌控。

---

## ✨ 核心特性

*   🗃️ **万物皆可集成 (Unified Hub)**：轻松收纳本地的 Docker、K8s、Git、各类 Lint、配置生成及自定义脚本，告别无休止的 `alias` 污染。
*   🧠 **图拓扑智能调度 (DAG Orchestration)**：无需手动编写复杂的链式命令。`devpulse` 自动解析工具间的依赖关系，计算出最优执行路径，支持无依赖工具的并发执行。
*   🤖 **AI 协同友好 (Prompt-Driven AI Co-pilot)**：内置“借鸡生蛋”式 AI 方案。一键将本地复杂的资产盘点 facts 转化为完美的 AI 提示词并自动复制到剪贴板，让任何大模型化身你的专属 DevOps 编排专家。
*   ⚡ **极致本地性能 (Zero Latency)**：100% 运行于本地，数据不出本地，免去网络延迟，保障绝对的隐私与安全。

---

## 🚀 智能 AI 协同工作流

由于 `devpulse` 保持轻量纯净，它不内置任何笨重的 AI 接口或网络请求，而是通过**提示词协同（Prompt Synergy）**将编排自由度完全交给你：

```text
[工作流图示]
+-----------------+       +---------------+       +---------------+       +------------------+

| 1. 终端敲击       |       | 2. 自动复制    |       | 3. 粘贴给 AI   |       | 4. 完美落地       |
| devpulse gather | ----> | 剪贴板(Prompt) | ----> |  任意本地/云端 | ----> | .devpulse.conf   |
|                 |       |               |       |  大模型AI助手  |       |                  |
+-----------------+       +---------------+       +---------------+       +------------------+
```

1. **盘点**：在项目根目录运行 `devpulse gather`。
2. **复制**：工具箱自动深度嗅探你当前的 OS、技术栈特征及环境变量，组装成结构化 Context 并**自动复制到系统剪贴板**。
3. **编排**：把内容粘贴给你常用的 AI（DeepSeek、ChatGPT、Claude 等），AI 会根据你的技术栈最佳实践和个人黑话喜好，生成一套编排好的 HOCON 配置。
4. **脉冲**：将配置保存，直接运行 `devpulse pulse` 启动本地调度！

---

## ⌨️ 常用命令

`devpulse` 的命令设计遵循肌肉记忆，短小且语义明确：

*   `devpulse gather` - 深度扫描本地已有的 DevOps 工具并自动复制 AI 协同提示词到剪贴板。
*   `devpulse init` - 手动初始化当前目录的本地工具箱默认配置。
*   `devpulse pulse [task]` - 智能解析依赖图（DAG）并执行指定的复合任务流。
*   `devpulse status` - 查看当前本地工具生态的健康状态与集成列表。

---

## 🛠️ 配置示例 (`.devpulse.conf`)

devpulse 仅支持 HOCON 配置，使用 Typesafe Config 解析，不支持 YAML。通过简单的声明，即可让 `devpulse` 理解工具之间的依赖，从而实现智能调度：

```hocon
version = 1
tools = [
  {
    name = linter
    cmd = "eslint . --fix"
  },
  {
    name = docker-build
    cmd = "docker build -t app:local ."
    needs = [linter] # 智能调度：只有 linter 成功后才会触发打包
  }
]

pipelines {
  dev {
    description = "本地开发快速智调"
    tasks = [linter, docker-build]
  }
}
```

---

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 协议开源。
