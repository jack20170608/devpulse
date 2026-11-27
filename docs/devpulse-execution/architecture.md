# devpulse-execution 架构 / devpulse-execution architecture

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

## 中文

### 位置

```
devpulse-core  ──┐
devpulse-storage ─┴─→ devpulse-execution ──→ pulse CLI
                                          └─→ devpulse-server（计划中）
```

`devpulse-execution` 依赖 `devpulse-core` 与 `devpulse-storage`，被 `pulse` 依赖。它不依赖任何 CLI 模块，因此可以被服务端直接复用。

### 分工

| 层 | 负责 | 不负责 |
|----|------|--------|
| core | DAG 调度、Bash 执行、进程与文件 | YAML、计划校验 |
| storage | 运行记录的持久化 | 业务语义、状态机 |
| **execution** | YAML → 计划 → 执行 → 记录 | 参数解析、输出渲染、退出码 |
| pulse CLI | picocli 参数、呈现、退出码 | 执行细节 |

### 关键类型

- `PulseConfiguration` 只做"读取与校验"，不执行；`PulsePlan` 只做"解析与闭包"，不调度。
- `RunService.execute` 是唯一的执行入口：它绑定 `BashTaskAdapter`、创建 `WorkflowRun`、安装取消钩子、写入复用索引。
- 取消语义由 `RunService` 内的 shutdown hook 维护；调用方无需关心。

### 刻意不做的事

- 不渲染终端输出：`PrintWriter` 由调用方传入，可以指向空流。
- 不决定退出码：`Outcome` 返回结果，分类留给 CLI 与服务端各自定义。
- 不发现 Bash：`BashShell` 由调用方传入，这样"缺少 Bash"能保持独立的环境错误分类。

## English

### Position

```
devpulse-core  ──┐
devpulse-storage ─┴─→ devpulse-execution ──→ pulse CLI
                                          └─→ devpulse-server (planned)
```

`devpulse-execution` depends on `devpulse-core` and `devpulse-storage`, and the `pulse` CLI depends on it. It depends on no CLI module, which is what lets a server reuse it directly.

### Division of labour

| Layer | Owns | Does not own |
|-------|------|--------------|
| core | DAG scheduling, Bash execution, processes and files | YAML, plan validation |
| storage | Persistence of run records | Business semantics, state machines |
| **execution** | YAML → plan → execution → recording | Argument parsing, rendering, exit codes |
| pulse CLI | picocli arguments, presentation, exit codes | Execution details |

### Key types

- `PulseConfiguration` reads and validates only; `PulsePlan` resolves and computes closure only.
- `RunService.execute` is the single execution entry point: it binds `BashTaskAdapter`, creates `WorkflowRun`, installs the cancellation hook, and writes the reuse index.
- Cancellation semantics live inside `RunService`; callers do not manage the hook.

### Deliberate omissions

- No terminal rendering: the caller passes the `PrintWriter`, possibly over a null stream.
- No exit codes: `Outcome` returns the result; classification belongs to each front end.
- No Bash discovery: the caller passes `BashShell`, so a missing Bash keeps its own environment classification.
