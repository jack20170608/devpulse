# 产品定位与竞品分析 / Positioning and competitive landscape

**状态 / Status**：设计参考，随产品演进更新 / Design reference, updated as the product evolves

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

## 中文

### 1. 一句话定位

devpulse 是**不需要容器、用原生 Bash 执行、带长驻进程管理的本地任务图**。

它不替代 shell，它替代你手写的编排逻辑：依赖顺序、并发上限、共享依赖去重、服务就绪门控、失败后的后继处理。

### 2. 与 shell 脚本的边界

| 维度 | shell 脚本 | devpulse |
|------|-----------|----------|
| 依赖表达 | `&&` 串行，靠书写顺序 | `needs`，拓扑排序，与顺序无关 |
| 共享依赖 | 手写判断，容易重复执行 | 自动去重，同一任务只跑一次 |
| 并行 | `&` + `wait`，无上限 | `parallelism`，超出排队 |
| 失败语义 | 全停 | 只跳过后继，独立分支继续 |
| 长驻服务 | `nohup` + 自行轮询端口 | `type: daemon` + `ready.cmd` 门控 |
| 历史 | 终端回滚 | 运行入库，可回查 |
| 日志 | 混流或自行重定向 | 每任务独立文件，可按行读取 |
| 错误分类 | 仅 `$?` | 分类退出码（配置／环境／任务／daemon／基础设施） |
| 预演 | 无 | `review` / `--dry-run` 静态验证整张图 |
| 团队共享 | 脚本散落 | `.devpulse.yaml` 进仓库 |

**不适用**：线性三步流程、需要 shell 循环与条件分支的复杂脚本、交互式操作。这些场景直接写 shell 更合适，套一层 YAML 只会增加噪声。

### 3. 竞品全景

"本地版 GitHub Actions"实际指三类不同的东西：

| 类别 | 代表 | 模型 | 关键代价 |
|------|------|------|----------|
| 本地跑 CI 配置 | [act](https://github.com/nektos/act) | 读 `.github/workflows`，在 Docker 中模拟 runner | 依赖 Docker，冷启动数十秒 |
| 同一定义本地与 CI 通用 | [Earthly](https://earthly.dev/)、[Dagger](https://dagger.io/) | 容器内构建，DAG + 缓存 | 依赖容器，学习成本高 |
| 任务图与增量缓存 | Nx、Turborepo、Bazel、[ztn](https://docs.rs/crate/ztn/1.3.3) | 输入哈希判定重跑，本地／远程缓存 | 多为特定生态，容器或构建系统绑定 |
| 轻量任务运行器 | Task、just、make、mise | 命令别名与简单依赖 | 无并行调度、无 DAG 语义、无缓存 |

### 4. devpulse 的空位

| 维度 | act | Earthly / Dagger | Nx / Bazel | devpulse |
|------|-----|------------------|------------|----------|
| 需要 Docker | 是 | 是 | 否 | **否** |
| 配置格式 | Actions YAML | Earthfile / 代码 | JS/TS 生态 | YAML |
| 长驻进程就绪门控 | 容器模拟 | 容器内 | 无 | **有** |
| 运行历史 | 无 | 云上 | 有 | 本地数据库 |
| Windows / Git Bash | 需 Docker Desktop | 需 Docker | 一般 | **显式支持** |
| 增量执行 | 无 | 有 | **有** | 尚未生效（见 §6） |

结论：唯一清晰的空位是"**无容器 + 原生 Bash + 单二进制 + 长驻进程管理**"这个组合。这个组合目前没有完整对手。

### 5. 产品设计准则

**必须坚持（差异化资产）**

- 不依赖容器运行时，Bash 就是执行单元。
- Bash 路径显式发现与校验，Windows 使用 Git Bash，绝不回退到 CMD/PowerShell/WSL。
- 长驻进程是一等公民：显式声明、就绪门控、手动停止、身份可校验。
- 单 JAR / 单原生二进制即可运行，不引入 Node、Python 等运行时。
- 本地状态可取回：历史、日志、daemon 记录都可查询。

**明确不做（避免进入别人的主场）**

- 容器隔离与可复现构建——那是 Earthly / Dagger 的领域。
- 通用构建缓存与远程执行——那是 Bazel / Nx 的领域。
- 解析并执行 GitHub Actions YAML——那是 act 的领域。
- 交互式 shell 或通用脚本语言——不做。
- 沙箱与权限隔离——不声称提供，安全边界如实说明。

**新功能判断标准（三条都满足才做）**

1. 是否强化"本地、无容器、Bash 原生、有状态"这四个特征？
2. 是否能在没有外部服务的情况下工作？
3. 是否让 daemon 场景更完整，而不是更复杂？

### 6. 当前差距（事实）

| 缺口 | 现状 | 影响 |
|------|------|------|
| 增量执行 | 复用索引只写不读，成功结果入库但从不查询 | 每次全量重跑，"增量"卖点未兑现 |
| 重试 | `retry` 只输出计划不执行 | 失败恢复闭环缺失 |
| 核心 Bash 层测试 | `BashShellTest` 整类 `@Disabled` | 最底层的执行路径无回归保护 |

前两项是这类工具的**入场券**，不是加分项。详见 [Retry 与恢复设计](retry-and-resume.md)。

### 7. 演进优先级

1. **接上复用索引**，实现按节点指纹的重跑与复用（入场券）。
2. **打磨 daemon 场景**：一条命令按依赖拉起本地环境，就绪后跑测试，退出时干净回收（差异化）。
3. **补齐核心测试**，让 Bash 执行层有回归保护。
4. 提供可复制的场景示例（本地开发环境、端到端测试、发布前检查）。

## English

### 1. Positioning in one sentence

devpulse is a **local task graph that needs no container runtime, executes through native Bash, and manages long-running processes**.

It does not replace the shell. It replaces the orchestration logic you hand-write: dependency order, concurrency limits, shared-dependency deduplication, service readiness gating, and what happens to downstream work after a failure.

### 2. Boundary with shell scripts

| Dimension | Shell script | devpulse |
|-----------|--------------|----------|
| Dependencies | `&&` chains, order matters | `needs`, topologically sorted, order-independent |
| Shared dependencies | Manual guards, easy to run twice | Deduplicated automatically |
| Parallelism | `&` and `wait`, no limit | `parallelism`, extra work queues |
| Failure semantics | Everything stops | Only descendants skip; independent branches continue |
| Long-running services | `nohup` plus hand-rolled port polling | `type: daemon` with `ready.cmd` gating |
| History | Terminal scrollback | Runs recorded, queryable |
| Logs | Interleaved or manually redirected | One file per task, readable line by line |
| Error classification | Only `$?` | Classified exit codes (config, environment, task, daemon, infrastructure) |
| Dry run | None | `review` / `--dry-run` validates the whole graph statically |
| Team sharing | Scripts scattered around | `.devpulse.yaml` lives in the repository |

**Not a fit**: linear three-step flows, scripts that need shell loops and branches, interactive work. A plain shell script is better there; wrapping it in YAML only adds noise.

### 3. The competitive landscape

"Local GitHub Actions" means three different things:

| Category | Examples | Model | Main cost |
|----------|----------|-------|-----------|
| Run CI config locally | [act](https://github.com/nektos/act) | Reads `.github/workflows`, simulates a runner in Docker | Needs Docker, tens of seconds to start |
| One definition local and in CI | [Earthly](https://earthly.dev/), [Dagger](https://dagger.io/) | Containerized builds, DAG with caching | Needs containers, steeper learning curve |
| Task graph with incremental caching | Nx, Turborepo, Bazel, [ztn](https://docs.rs/crate/ztn/1.3.3) | Input hashes decide what reruns, local or remote cache | Tied to an ecosystem or build system |
| Lightweight task runners | Task, just, make, mise | Command aliases with simple dependencies | No scheduling, no DAG semantics, no cache |

### 4. The niche devpulse occupies

| Dimension | act | Earthly / Dagger | Nx / Bazel | devpulse |
|-----------|-----|------------------|------------|----------|
| Needs Docker | Yes | Yes | No | **No** |
| Configuration | Actions YAML | Earthfile / code | JS/TS ecosystem | YAML |
| Daemon readiness gating | Emulated by containers | Inside containers | None | **Yes** |
| Run history | No | In the cloud | Yes | Local database |
| Windows / Git Bash | Needs Docker Desktop | Needs Docker | Partial | **Explicit** |
| Incremental execution | No | Yes | **Yes** | Not effective yet (see §6) |

The only clear gap is the combination of **no container runtime, native Bash, a single binary, and long-running process management**. Nothing currently covers that combination end to end.

### 5. Product design rules

**Hold on to (the differentiators)**

- No container runtime; Bash is the execution unit.
- Bash is discovered and validated explicitly; Windows uses Git Bash and never falls back to CMD, PowerShell, or WSL.
- Long-running processes are first-class: declared explicitly, readiness-gated, stopped manually, identity verified.
- One JAR or one native binary runs everything; no Node, Python, or other runtime.
- Local state is retrievable: history, logs, and daemon records can all be queried.

**Deliberately out of scope (do not enter another tool's home turf)**

- Container isolation and reproducible builds — that is Earthly and Dagger.
- General build caching and remote execution — that is Bazel and Nx.
- Parsing and running GitHub Actions YAML — that is act.
- An interactive shell or a general scripting language.
- Sandboxing or privilege isolation — do not claim it; describe the security boundary honestly.

**A new feature must satisfy all three**

1. Does it strengthen "local, container-free, native Bash, stateful"?
2. Does it work with no external service?
3. Does it make the daemon story more complete rather than more complex?

### 6. Current gaps (facts)

| Gap | Status | Impact |
|-----|--------|--------|
| Incremental execution | The reuse index is written but never read | Every run redoes everything; the "incremental" claim is unfulfilled |
| Retry | `retry` reports a plan without executing | The failure-recovery loop is missing |
| Core Bash tests | `BashShellTest` is disabled as a whole | The lowest execution layer has no regression cover |

The first two are table stakes for this category, not bonuses. See [Retry and resume design](retry-and-resume.md).

### 7. Evolution priorities

1. **Wire up the reuse index** so nodes rerun or are reused by fingerprint (table stakes).
2. **Polish the daemon story**: one command brings up a local environment in dependency order, runs tests once ready, and cleans up on exit (the differentiator).
3. **Restore core test coverage** so the Bash execution layer is protected.
4. Provide copyable scenarios (local development environment, end-to-end tests, pre-release checks).
