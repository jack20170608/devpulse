# 产品形态：一个二进制，服务端 + 瘦客户端 / Product shape: one binary, a server and a thin client

**状态 / Status**：已定，未实施 / Decided, not implemented

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

本文记录 2026-11-26 的架构讨论结论。存储模型见 [客户端/服务端模型与 v3 存储](client-server-model.md)。

## 中文

### 1. 决定台账

| # | 决定 | 理由 |
|---|---|---|
| 1 | 独立工具归入 `devpulse-tools`；实现收敛后只剩 Jira | 工具与插件是两个概念，目录名要能区分 |
| 2 | 删除 `gather` | 295 行主代码、无测试；环境盘点能力并入 `doctor` |
| 3 | `BashShell` 与 Bash 执行能力随执行器插件；core 只管理抽象资源 | core 里不应出现任何进程启动代码 |
| 4 | YAML 退化为纯任务数据模式（`kind` + 私有 `spec`） | schema 只定义“任务是什么”，不定义“怎么跑” |
| 5 | `pulse` 退化为本产品客户端 | 执行、写库、事件归服务端 |
| 6 | `status --tools` 改为 `devpulse doctor`，分本地诊断与服务端报告两段 | 排障命令不能依赖它要诊断的东西 |
| 7 | 客户端与服务端统一为一个二进制 `devpulse`，不加短别名；`pulse` 名字退役 | 版本永远一致；少一个故障源 |
| 8 | `review` 与 `--dry-run` 走服务端 API | 配置语义只有一份 |
| 9 | 服务端由 `server start` 显式启动；是否自动拉起留到客户端改走服务端时再定 | 显式启动可预测，隐藏进程难排查 |
| 10 | 产物形态：现阶段只发 **JVM JAR**；GraalVM 原生镜像推迟到产品形态稳定后 | 形态还在变，native 的反射元数据与构建参数会持续返工 |

### 2. 一个二进制

```text
devpulse run [TARGET]        提交并跟随（默认动作）
devpulse status              查询运行记录
devpulse review [TARGET]     静态审阅（服务端解析）
devpulse init                生成配置模板（本地）
devpulse stop                停止长驻进程
devpulse doctor              本地诊断 + 服务端环境报告
devpulse server start|stop|status   常驻进程
devpulse version
```

服务端不是独立程序，而是 `devpulse server` 子命令。统一的意义不只是少装一个文件：**客户端与服务端永远是同一版本**，而这正是这类架构最常见的故障来源。

代价是二进制合体后，客户端会带上服务端的全部代码（HTTP、存储、执行器），native 镜像体积上升；启动速度基本不受影响。

`pulse` 这个名字退役：`pulse init` 变成 `devpulse init`。项目仍在 pre-1.0，一次改干净比长期双名并存便宜。名字较长，需要随包提供 shell 补全。

### 3. 服务端与客户端的分工

| 能力 | 客户端 | 服务端 |
|---|---|---|
| 参数解析、渲染、退出码 | ✅ | |
| 提交运行、跟随事件、取消 | ✅（HTTP/SSE） | ✅（执行） |
| 解析 YAML、建图、校验 | | ✅ |
| 执行任务、管理长驻进程 | | ✅ |
| 唯一写库 | | ✅ |
| `init` 写模板 | ✅（纯文件操作） | |
| `doctor` 本地诊断 | ✅ | |
| `doctor` 环境报告 | | ✅ |

客户端不持有任何进程、不打开数据库。这意味着它可以在另一台机器上运行——首版不做远程，但边界已经立住。

### 4. 服务端生命周期：显式 start

```text
devpulse server start   → 后台拉起自身，等待就绪（超时 10s），打印端口
devpulse server status  → 读状态文件 + 探活
devpulse server stop    → 发 SIGTERM，等进程退出并清理状态文件
devpulse server run     → 前台运行（调试用）
```

服务的边界：绑定 `127.0.0.1`，默认端口 **12580**，端口被占用时报错并提示 `--port`；**不做认证**，只用 Host 校验 + `X-Devpulse-Client` 头挡住跨站请求与 DNS rebinding。

**自动拉起暂不实现**。等客户端命令真正改为“提交到服务端”时再决定是否引入（届时需要区分启动失败、版本不匹配、拉起竞争三种情形）。

### 5. `review` 与 `--dry-run` 走服务端

两者都需要解析 YAML、建图、校验。放在服务端意味着配置语义只有一份，客户端不必打包配置解析器。

代价是“没服务端就不能校验配置”；在显式 `server start` 的模型下，这就是一条明确的报错，而不是隐式的启动延迟。

### 6. `doctor` 两段式

排障命令不能依赖它要诊断的东西：

| 段 | 位置 | 检查内容 |
|---|---|---|
| 本地诊断 | 客户端 | 服务端进程是否存在、端口是否可连、客户端/服务端版本是否一致 |
| 环境报告 | 服务端 | bash 路径、17 个工具链（git、docker、kubectl、helm、node、npm、pnpm、yarn、java、mvn、gradle、python、python3、go、cargo、make、terraform）、项目标记文件 |

服务端不可达时，本地诊断照常输出，并明确说明“服务端未运行”，而不是整条命令失败。

### 7. YAML 是纯数据

平台不理解 `run`、`ready` 这些词，它们属于执行器的私有数据：

```yaml
version: 1
parallelism: 4

tasks:
  build:
    description: 编译前端
    needs: []
    kind: bash
    spec:
      run: ./mvnw -q package

  db:
    description: 本地数据库
    needs: []
    kind: bash
    spec:
      service:
        run: docker compose up db
        ready: { cmd: pg_isready, interval: 1s }
```

| 层 | 校验什么 |
|---|---|
| 平台 | `version`、`parallelism`、任务名唯一、`needs` 指向存在、无环、`kind` 已注册、`spec` 是映射 |
| 执行器 | `spec` 的内部结构、必填字段、未知字段 |

两个连带要求：

1. **执行指纹必须包含执行器身份**：`hash(kind, 规范化 spec, 执行器版本)`。否则换了执行器实现，复用索引会错误命中。
2. **`review` 的展示由执行器提供**：现在它打印 `task.command()`，之后要问执行器“这个 spec 怎么展示”。

### 8. 目标模块树

```text
devpulse-core          抽象：DAG、调度、运行生命周期、资源模型、事件     [服务端]
devpulse-storage       持久化                                        [服务端]
devpulse-spi           执行器 / 能力契约                              [服务端]
devpulse-config        YAML → 通用任务文档（纯数据 + 图校验）           [服务端]
devpulse-execution     装配与驱动（RunService）                       [服务端]
devpulse-plugins/
  executor-bash        BashShell、BashTaskAdapter、DaemonManager、bash spec
devpulse-server        常驻进程：执行 + 唯一写库 + 事件流 + HTTP API
devpulse-tools/jira    独立工具，零平台依赖，可独立发展
devpulse                一个二进制：客户端命令 + server 子命令
devpulse-dist          发行
```

### 9. 实施顺序

每个阶段独立可验证，行为变化集中在阶段 0 与 3。

| 阶段 | 内容 | 行为变化 |
|---|---|---|
| 0 | 删除 `gather`（含 bom、cli 注册、poc 的未使用依赖、build.sh、文档） | `gather` 命令消失 |
| 1 | `status --tools` → `devpulse doctor`（两段式，仍在同一进程） | 命令改名 |
| 2 | 统一二进制：`devpulse-cli` → `devpulse`，`pulse` 退役为默认动作 | 命令名变化 |
| 3 | 服务端骨架：`server start` + HTTP + 自动拉起；`run` 仍可本地执行 | 新增能力 |
| 4 | 执行迁到服务端：客户端只走 API；`review`/`--dry-run` 改走 API | **客户端不再本地执行** |
| 5 | `core.bash` 正名（`RunHistory`/`WorkflowRun`/`PlanManifest` → `core.run`），Bash 执行器抽成插件 | 内部结构 |
| 6 | YAML 切到 `kind`/`spec`，校验下沉 | **配置破坏性变更** |

阶段 0、1、2 与插件化无关，可以先做。

### 10. 风险与不做的事

| 风险 | 应对 |
|---|---|
| 二进制合体后 native 体积上升 | 接受；发行包说明体积变化 |
| 自动拉起带来隐性启动延迟 | 就绪超时 10s，失败信息必须指向服务端日志 |
| 单机多用户同时使用 | 首版不支持；`requested_by` 字段先记录，不以此做隔离 |
| `pulse` 退役破坏既有用法 | pre-1.0 一次改干净，文档与 CHANGELOG 注明 |

**不做**：运行期热插拔插件（与将来的 native 冲突）、远程执行、多用户权限、容器隔离。

## English

### 1. Decisions

| # | Decision | Why |
|---|---|---|
| 1 | Independent tools move to `devpulse-tools`; after the cleanup only Jira remains | Tools and plugins are different concepts |
| 2 | Delete `gather` | 295 lines, no tests; its inventory ability folds into `doctor` |
| 3 | `BashShell` and Bash execution move with the executor plugin; core keeps abstract resources only | core must contain no process spawning |
| 4 | YAML becomes plain task data (`kind` plus a private `spec`) | The schema defines what a task is, not how it runs |
| 5 | `pulse` becomes this product's client | Execution, writes and events belong to the server |
| 6 | `status --tools` becomes `devpulse doctor`, split into local diagnostics and a server report | A diagnostic command cannot depend on what it diagnoses |
| 7 | One binary `devpulse` for client and server, no short alias; the `pulse` name retires | Versions can never diverge |
| 8 | `review` and `--dry-run` go through the server API | Configuration semantics exist once |
| 9 | The server starts explicitly with `server start`; auto-start is revisited when client commands move to the server | An explicit start is predictable; a hidden process is hard to debug |
| 10 | Artifact: the **JVM JAR** only for now; the GraalVM native image is deferred until the product shape settles | The shape is still moving, so native reflection metadata and build flags would keep changing |

### 2. One binary

`run`, `status`, `review`, `init`, `stop`, `doctor`, `server start|stop|status`, `version`. The server is a subcommand, not a second program. The point is not saving a file: the client and the server are always the same version, which is the most common failure mode of this architecture.

The cost is that the client binary carries the server's code, so the native image grows; startup speed barely changes. The `pulse` name retires, so shell completion matters.

### 3. Division of labour

The client parses arguments, renders, maps exit codes, and talks HTTP/SSE. The server parses YAML, builds the graph, executes, owns long-running processes, and is the only writer. `init` and local diagnostics stay in the client. The client holds no process and opens no database, so the boundary already allows a remote client later — the first version does not build one.

### 4. Auto-start

`server start` relaunches the binary in the background, waits up to ten seconds for readiness and prints the port; `status` reads the state file and probes liveness; `stop` sends SIGTERM, waits for exit and clears the state file; `run` stays in the foreground for debugging. The server binds `127.0.0.1` on port 12580 by default, reports a busy port as an error, and has no authentication: a Host check plus the `X-Devpulse-Client` header are the boundary. Auto-start is deliberately not implemented yet; it is revisited when client commands move to the server.

### 5. Doctor

Local diagnostics (process, port, version) always run; the environment report (bash, seventeen tools, project markers) comes from the server. When the server is unreachable, the command says so instead of failing.

### 6. YAML as plain data

The platform validates the document and the graph: version, parallelism, unique names, existing prerequisites, acyclicity, a registered `kind`, and `spec` being a mapping. Each executor validates its own `spec`. Consequently the execution fingerprint must include the executor identity (`kind`, normalized spec, executor version), and `review` must ask the executor how to render its spec.

### 7. Modules and order

core, storage, spi, config, execution, `plugins/executor-bash`, server, `tools/jira`, and one `devpulse` binary. The order is: delete gather, rename `status --tools` to `doctor`, unify the binary, add the server, move execution behind it, rename `core.bash` and extract the executor plugin, then switch YAML to `kind`/`spec`. The first three phases are independent of the plugin work.

### 8. Risks and non-goals

The native image grows; auto-start adds hidden latency, so readiness failures must point at server logs; multiple users on one machine are unsupported, and `requested_by` records who ran what without enforcing isolation. Not doing: runtime plugin loading, remote execution, multi-user permissions, container isolation.
