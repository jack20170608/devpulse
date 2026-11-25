# devpulse-execution 使用说明 / devpulse-execution usage

[中文](#中文) | [English](#english)

## 中文

### 这个模块是什么

Pulse 的执行内核：把 `.devpulse.yaml` 变成可执行计划，并把一次执行完整记录下来。

它不注册命令，也不做终端渲染。`pulse` CLI 与将来的 `devpulse-server` 共用这一层，这样"执行一次运行"只有一个实现。

### 内容

| 类型 | 职责 |
|------|------|
| `PulseConfiguration` / `YamlConfiguration` | 读取并校验 YAML，转换为 Typesafe Config |
| `PulsePlan` | 解析目标、依赖闭包与图校验 |
| `TaskFingerprints` | 计算逐节点执行指纹 |
| `RetryPlan` | 依据父运行与指纹计算重跑/复用集合 |
| `RunService` | 执行一次运行：绑定 Bash 适配器、记录历史、写入复用索引 |

### 使用

```java
var outcome =
        RunService.execute(shell, directory, target, plan, concurrency, console, id -> {});
```

调用方保留三件事，模块不替它决定：

1. **Bash 发现与错误分类**——缺少 Bash 是环境问题，不是执行失败。
2. **计划构建**——配置无效与目标不存在属于不同的错误类别。
3. **结果呈现**——文本、JSON 或 HTTP 响应由调用方决定。

`RunService.execute` 抛出的 `IOException` 与 `InterruptedException` 保持原语义：前者是基础设施故障，后者是取消。

## English

### What this module is

The Pulse execution kernel: it turns `.devpulse.yaml` into an executable plan and records a run completely.

It registers no commands and renders nothing. The `pulse` CLI and the future `devpulse-server` share this layer, so "run once" has a single implementation.

### Contents

| Type | Responsibility |
|------|----------------|
| `PulseConfiguration` / `YamlConfiguration` | Read and validate YAML, converting it to Typesafe Config |
| `PulsePlan` | Resolve the target, the dependency closure, and graph validation |
| `TaskFingerprints` | Compute per-node execution fingerprints |
| `RetryPlan` | Decide what to rerun or reuse from a parent run and its fingerprints |
| `RunService` | Execute one run: bind the Bash adapter, record history, publish the reuse index |

### Usage

```java
var outcome =
        RunService.execute(shell, directory, target, plan, concurrency, console, id -> {});
```

The caller keeps three decisions:

1. **Bash discovery and error classification** — a missing Bash is an environment problem, not a failed run.
2. **Plan construction** — an invalid configuration and a missing target are different error classes.
3. **Result rendering** — text, JSON, or an HTTP response belongs to the caller.

`RunService.execute` keeps its exceptions honest: `IOException` means infrastructure, `InterruptedException` means cancellation.
