# devpulse-plugin/bash 架构设计 / devpulse-plugin/bash architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../../architecture.md) | [使用说明](../../../devpulse-plugin/bash/README.md)

### 模块边界

`devpulse-plugin/bash` 是 **Bash 执行插件**，包名 `io.github.devpulse.bash`。它依赖 `devpulse-core`（调度）与 `devpulse-storage`（Daemon 记录），不依赖 `devpulse-execution`，因此执行器与运行记录层之间不存在环。

| 类 | 职责 |
|----|------|
| `BashShell` | 定位并校验 Bash，提供一次性执行、脚本文件执行与探测 |
| `BashTaskAdapter` | 把 `BashTaskDefinition` 绑定成 `DagTask<Integer>` / `DagDaemon<ProcessHandle>` |
| `DaemonManager` | 长驻进程的启动、就绪、健康观测与手动停止 |
| `EnvironmentGatherer` | 采集 OS、架构、Java、Bash、工具位置与项目标志 |
| `TaskHistory` | 执行器写运行记录的最小接口（依赖倒置的接缝） |

### 依赖倒置：`TaskHistory`

执行器需要写运行记录，但运行记录由 `devpulse-execution` 实现。若执行器直接依赖它，会形成 `execution → bash → execution` 的环。因此本模块定义 `TaskHistory`（`id`、`directory`、`taskId`、`started`、`openLog`、`processExited`），`devpulse-execution` 的 `RunHistory.Writer` 实现它。执行器只看见这个接缝，可以用别的记录实现替换，也可以在没有历史的情况下运行。

### Bash 基础组件（`BashShell.java`）

- **统一解释器**：所有 OS 均使用 Bash，Windows 使用 Git Bash；找不到可用 Bash 时明确报错，不回退到 `sh`、CMD、PowerShell 或 WSL。
- **定位顺序**：显式 `--bash` 路径优先，其次探测 PATH；Windows 额外检查 Git 安装位置，自动探测只接受具有 Git 安装标记的 Bash，避免误选 WSL 入口。
- **可用性验证**：通过短时子进程验证 Bash 能实际启动，而不是仅检查文件存在。
- **启动方式**：使用 `ProcessBuilder` 启动 `bash --noprofile --norc -s --`，普通任务通过标准输入写入完整 UTF-8 脚本后关闭输入；长驻进程、就绪和停止命令通过保存的脚本文件重定向标准输入。不自行按空格拆分，也不将命令文本放入 `-c` 参数，避免 Windows 命令行转义改变引号和变量表达式；不提供交互输入。
- **环境与目录**：使用 `ProcessBuilder.directory()` 设置工作目录，继承父进程环境，但移除 `BASH_ENV`、`ENV` 和 `BASH_FUNC_*`，避免自动执行环境指定的启动文件或导入用户函数。
- **探测资源控制**：Bash 验证与环境扫描均设置超时，持续读取扫描输出，并清理探测进程。

### 环境事实采集（`EnvironmentGatherer.java`）

`gather()` 校验并规范化目录，通过固定的 Bash `command -v` 脚本获取工具位置，拒绝不完整盘点，并检查根目录项目标志；返回防御性复制的 `Facts` / `Tool` record，其中 `Facts.bash` 是 `BashShell` 的发现结果。采集不读取项目内容、不递归扫描、不采集环境变量值、不执行版本或健康检查。功能与使用契约见 [模块使用说明](../../../devpulse-plugin/bash/README.md#环境事实采集)。

### Daemon 管理（`DaemonManager.java`）

长驻进程写入 `.devpulse/daemons/<id>` 目录，身份由 PID 加进程启动时间确定；就绪通过显式的 `ready.cmd` 判定，就绪不是跨进程检查点。健康检测按需执行，没有后台调度器；Daemon 从不自动停止，用户通过 `stop` 显式停止。进程输出写入任务日志，日志与元数据不自动删除。

### 与上层的分界

本模块不读取 Pulse YAML、不做配置校验、不决定退出码、不渲染终端输出。任务定义如何从配置装配、失败如何映射到分类退出码，都归 `devpulse-execution` 与 `devpulse-cli`。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugin/bash/README.md)

### Module boundaries

`devpulse-plugin/bash` is the **Bash execution plugin** in the `io.github.devpulse.bash` package. It depends on `devpulse-core` (scheduling) and `devpulse-storage` (daemon records) but **not** on `devpulse-execution`, so there is no cycle between the executor and the run-record layer.

| Class | Role |
|-------|------|
| `BashShell` | Locates and validates Bash, runs one-shot commands, writes script files, probes the host |
| `BashTaskAdapter` | Binds `BashTaskDefinition` to `DagTask<Integer>` / `DagDaemon<ProcessHandle>` |
| `DaemonManager` | Starts daemons, tracks readiness and health, and stops them on request |
| `EnvironmentGatherer` | Collects OS, architecture, Java, Bash, tool locations, and project markers |
| `TaskHistory` | The minimal run-record interface the executor writes through |

### Dependency inversion: `TaskHistory`

The executor has to record runs, but the run record belongs to `devpulse-execution`. Depending on it directly would create `execution → bash → execution`. This module therefore defines `TaskHistory` (`id`, `directory`, `taskId`, `started`, `openLog`, `processExited`) and `RunHistory.Writer` in `devpulse-execution` implements it. The executor sees only this seam, so another recorder can replace it, and the executor can also run without history.

### Bash component (`BashShell.java`)

- **One interpreter everywhere**: every OS uses Bash, with Git Bash on Windows; a missing or unusable Bash is a clear error, never a fallback to `sh`, CMD, PowerShell, or WSL.
- **Discovery order**: an explicit `--bash` path wins, then PATH; on Windows the Git installation is checked too, and auto-discovery only accepts a Bash carrying Git installation markers so a WSL entry point is never picked by accident.
- **Availability check**: a short child process proves Bash actually starts, rather than merely that a file exists.
- **Launch**: `ProcessBuilder` runs `bash --noprofile --norc -s --`. Ordinary tasks receive the whole UTF-8 script on stdin before it is closed; daemons, readiness checks, and stop commands redirect stdin from a saved script file. The command text is never split on spaces or passed to `-c`, so Windows command-line escaping cannot rewrite quoting or variable expressions, and no interactive input is offered.
- **Environment and directory**: `ProcessBuilder.directory()` sets the working directory; the parent environment is inherited except `BASH_ENV`, `ENV`, and `BASH_FUNC_*`, so environment-specified startup files never run and user functions are never imported.
- **Probe resource control**: Bash validation and environment scanning both use timeouts, keep draining scan output, and clean up probe processes.

### Environment fact collection (`EnvironmentGatherer.java`)

`gather()` validates and normalises the directory, resolves tool locations through a fixed Bash `command -v` script, rejects an incomplete inventory, and checks for project markers at the root. It returns defensively copied `Facts` / `Tool` records, where `Facts.bash` is the `BashShell` discovery result. Collection never reads project contents, walks recursively, captures environment variable values, or runs version and health checks. See [module usage](../../../devpulse-plugin/bash/README.md#environment-fact-collection).

### Daemon management (`DaemonManager.java`)

A daemon writes to `.devpulse/daemons/<id>`; identity is the PID plus process start time. Readiness is decided by an explicit `ready.cmd`, and readiness is not a cross-process checkpoint. Health checks run on demand with no background scheduler; daemons are never stopped automatically and users stop them explicitly with `stop`. Output goes to the task log, and logs and metadata are never deleted automatically.

### Boundary with the layers above

This module reads no Pulse YAML, validates no configuration, decides no exit codes, and renders nothing. How definitions are assembled from configuration and how failures map to categorized exit codes belongs to `devpulse-execution` and `devpulse-cli`.
