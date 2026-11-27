# Pulse 使用说明 / Pulse usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../../readme.MD) | [架构设计](pulse-commands-architecture.md)

Pulse 读取项目的 YAML 任务配置，通过共享 Bash 运行时执行批任务及本地长驻进程。这些命令由统一的 `devpulse` 入口提供；Jira 不在其中。

### 环境与构建

构建需要 JDK 21+、Maven 和 Bash（Windows 使用 Git Bash）；JAR 运行需要 Java 21+。实际任务执行和 `doctor` 需要 Bash；`init`、`review`、`--dry-run`、历史列表及日志读取不需要 Bash。就绪检查及自定义停止命令使用启动时保存的 Bash 路径。

从仓库根目录运行：

```powershell
bash build.sh pulse
java -jar devpulse\target\devpulse.jar --help
```

以下所有构建及 JAR 示例均从仓库根目录运行；安装后用 `devpulse run [TARGET]` 或 `devpulse init|review|status|stop|doctor`。安装见 [发行与安装](../../devpulse-dist/README.md)，统一入口见 [devpulse CLI](../../devpulse-cli/README.md)。

### `init`：创建配置模板

**适用场景**：你知道项目的检查、构建等命令，希望先生成配置骨架，再自己填写。

`init` 在指定目录创建 UTF-8 编码的 `.devpulse.yaml`，写入固定的 YAML 模板。它不扫描项目、不调用 AI、不安装工具，也不执行任务。

模板包含 `version: 1`、`parallelism: 4`、`check` 和 `build` 两个任务，以及一个 `dev` 流水线。`build` 通过 `needs: [check]` 声明对 `check` 的依赖。**任务命令只是打印消息的 `echo` 占位符，不会进行实际检查或构建，需要替换为项目的真实命令。**

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 指定配置写入目录，默认为当前目录；目录必须已经存在 |
| `--force` | 用默认模板覆盖已有的普通配置文件；不会覆盖目录或符号链接 |

```powershell
# 在当前目录创建配置模板
java -jar devpulse\target\devpulse.jar init

# 在已有项目目录创建配置模板
java -jar devpulse\target\devpulse.jar init --directory "D:\your-project"

# 覆盖已有配置，原内容将丢失
java -jar devpulse\target\devpulse.jar init --directory "D:\your-project" --force
```

成功时输出配置文件路径并返回退出码 `0`。目录不存在、配置已存在且未指定 `--force`，或文件无法写入时，输出错误并返回 `1`。

### `pulse`：执行任务或流水线

`devpulse run [TARGET]` 从项目目录读取 `.devpulse.yaml`，默认执行名为 `dev` 的流水线或任务。自动补齐传递依赖，共享依赖和重复目标只执行一次；普通依赖必须成功退出，长驻进程依赖必须通过就绪检查，下游才会启动。独立任务可在并发上限内同时执行。

执行前校验完整配置，包括字段类型、未知字段、重复任务名、未知依赖和循环依赖。目标同时是任务名和流水线名时拒绝执行。配置错误不会启动任务。

| 参数 | 作用 |
|------|------|
| `TARGET` | 任务或流水线名称，默认 `dev` |
| `--directory <目录>` | 项目配置目录，默认当前目录 |
| `--bash <路径>` | 显式指定 Bash；Windows 使用 Git Bash |
| `--jobs <N>` | 正整数并发上限，覆盖配置中的 `parallelism`，内置默认值为 `4` |
| `--dry-run` | 仅校验并打印计划和命令，不探测 Bash、不执行任务、不创建运行记录 |
| `--format <FORMAT>` | 结果格式，默认 `text`；`json` 时标准输出只打印一个 JSON 文档，任务日志只写入日志文件 |

```powershell
java -jar devpulse\target\devpulse.jar
java -jar devpulse\target\devpulse.jar build --jobs 2
java -jar devpulse\target\devpulse.jar dev --directory "D:\your-project" --dry-run
```

任务通过非登录、非交互 Bash 执行，工作目录为项目配置目录。普通任务的输出和错误流合并，控制台日志带任务名前缀，并保存 UTF-8 日志；长驻进程的合并输出直接写入日志文件，不在控制台实时转发，以便 CLI 返回后仍能输出。普通任务以 Bash 退出码判断结果；脚本提前 `exit 0` 不会因输入管道关闭而被误判为失败。不隐式启用 `errexit` 或 `pipefail`，需要时由命令显式设置。

普通任务失败后跳过其后继，独立分支继续；Daemon 就绪后失效会取消正在执行的受影响后继并跳过未启动的后继。日志、启动或持久化基础设施故障会取消本次运行。全部节点成功且 Daemon 最后检测正常返回 `0`。失败时返回可供程序判定的分类退出码：配置整体无效 `10`，依赖图或字段值错误 `11`，目标不存在或歧义 `12`，缺少 Bash 等环境能力 `20`，任务失败 `30`，Daemon 未在启动时限内就绪 `31`，Daemon 就绪后失效 `32`，运行记录或日志等本地基础设施故障 `40`，协调线程中断 `130`。Daemon 问题优先于普通任务失败。搭配 `--format json` 时，同样的分类还会以 `code`、`file` 和 `line` 出现在错误文档中，运行结果的 `daemonOutcome` 字段给出 `NONE`、`NEVER_READY` 或 `LOST`。取消时尽力清理批任务进程及其可见后代，不停止 Daemon。所有执行由 core 的 `DagRun` 统一调度。

支持非交互批任务和显式声明的本地长驻进程，不提供普通批任务超时或沙箱。运行前必须审核命令；并发上限限制批任务和长驻进程的启动等待阶段，不限制已就绪的长驻进程数量、探测进程或任务内部的并发。

### 本地长驻进程

在任务中声明 `type: daemon`，将依赖满足条件从“成功退出”改为“通过就绪检查”。省略 `type` 或使用 `type: task` 保持原有批任务行为；批任务不能配置长驻进程字段。

以下配置以项目已有的 Node 测试服务器为例，命令和健康接口必须按实际项目调整；不会自动安装 Node 或创建服务器代码：

```yaml
version: 1
parallelism: 1
tasks:
  server:
    type: daemon
    cmd: "node server.js"
    ready:
      cmd: "curl --fail --silent --max-time 2 http://127.0.0.1:3000/health"
      interval: 500ms
      timeout: 30s
      checkTimeout: 2s
    stopTimeout: 5s
  check:
    cmd: "node check.js"
    needs: [server]
pipelines:
  dev:
    tasks: [check]
```

| 字段 | 含义 |
|------|------|
| `ready.cmd` | 必填；退出码 `0` 表示就绪，命令必须是快速、只读检查 |
| `ready.interval` | 检查间隔，默认 `500ms` |
| `ready.timeout` | 启动及初次就绪等待总时限，默认 `30s` |
| `ready.checkTimeout` | 每次检查的时限，默认 `2s`；启动阶段受剩余总时限约束 |
| `stop` | 可选 Bash 停止命令；未配置时终止已确认身份的进程及可见后代 |
| `stopTimeout` | 停止命令和退出等待的宽限时间，默认 `5s`；随后尽力强制终止并再确认最多 `2s` |

检查或停止命令超时后，回收该命令进程及可见后代可额外等待最多 `2s`；这段回收时间不包含在配置的命令时限内。

长驻进程命令必须保持前台运行，不使用 `&`、`nohup` 或自行 fork/脱离进程树。达到就绪后释放普通执行槽，因此 `--jobs 1` 也能运行依赖服务器的检查。DAG 在执行期间持续检查存活和就绪状态：意外退出（包括退出码 `0`）、检查失败或超时会跳过未启动的传递后继，并取消仍在执行的受影响任务；独立分支继续。依赖它的已就绪 Daemon 被标记为不可用并停止监测，但不会被停止。结束前进行最终健康检查，不自动重启，也不接管已有实例或复用被占用的端口。

工作流成功、失败、取消或启动检查超时后均不停止已记录的 Daemon，由用户通过 `stop` 手动管理；`shutdownHook` 配置已移除，出现该字段会报错。监测随运行结束，不存在独立后台调度器。初始化元数据失败时，会尽力终止尚未可靠记录的进程，避免产生无法管理的实例。强制杀死 CLI、断电或已脱离进程树的进程不保证回收。

历史任务状态 `READY` 保留就绪阶段成功的事实；`status` 另行展示本次运行记录的健康变化和按需检测的实时状态。Daemon 后续失效不会覆盖已完成节点结果，但会使本次工作流失败；历史健康只代表记录时刻。后续批任务重试/超时配置、通用 DAG 持久化恢复、条件分支、动态图、子工作流和显式 Daemon 重启路线见 [核心使用说明](../../devpulse-core/README.md)。

检查和停止命令可使用 `DEVPULSE_DAEMON_PID`、`DEVPULSE_DAEMON_INSTANCE_ID`、`DEVPULSE_RUN_ID` 和 `DEVPULSE_TASK_NAME`。停止命令应只操作对应实例；不要按名称批量终止进程，也不要自动删除数据库数据目录或 volume。停止命令失败或超时后仍尽力回收已记录进程，返回失败并保留诊断。监测时限由 DAG 唯一管理，实例记录保存实际策略；显式启动重试保留旧实例身份、独立脚本和手动停止入口，任务日志追加。

### `stop`：手动清理长驻进程

```powershell
# 停止最新运行的所有长驻进程
java -jar devpulse\target\devpulse.jar stop

# 停止指定运行中 server 任务的全部实例
java -jar devpulse\target\devpulse.jar stop server --run "<run-id>"

# 只停止 server 的指定实例（实例 ID 可从 status 获取）
java -jar devpulse\target\devpulse.jar stop server --run "<run-id>" --instance "<instance-id>"

# 停止其他项目的指定运行
java -jar devpulse\target\devpulse.jar stop --directory "D:\your-project" --run "<run-id>"
```

`stop [TASK]` 支持 `--directory` 和 `--run`，默认选择最新运行；任务级停止处理该任务全部记录实例，省略任务名按逆依赖顺序处理全部任务。`--instance <ID>` 必须与任务名一起使用，只停止属于该任务的指定实例。停止使用已保存配置，不依赖当前 `.devpulse.yaml`，不清理其他历史运行或删除日志、业务数据。PID 和启动时间用于核验身份，身份不确定时拒绝清理；单个实例清理失败仍继续处理其他实例并汇报失败。旧单实例记录仍可查询和停止。

已退出实例重复清理返回 `0`；不存在任何运行或所选运行没有长驻进程也返回 `0`。指定任务未记录进程、停止命令失败或无法确认清理结果返回 `1`；中断返回 `130`。手动停止仍被执行中的 DAG 使用的长驻进程，会触发受影响分支的取消与跳过。

### `retry`：查看失败运行的重试计划

`retry` 读取该运行的清单 `plan.conf`，按**逐节点执行指纹**判断当前配置中哪些节点必须重跑：父运行中失败的节点、指纹变化的节点、daemon 节点（接管尚未实现），以及上述全部节点的后继。其余节点进入复用集合。

当前阶段**只报告计划，不执行任何命令**：必须传入 `--dry-run`，否则返回退出码 `13`。

| 参数 | 作用 |
|------|------|
| `RUN_ID` | 要重试的运行 id |
| `--directory <目录>` | 项目配置目录，默认当前目录 |
| `--dry-run` | 只输出重跑与复用集合；本阶段必填 |
| `--force` | 允许对仍在执行的运行生成计划 |
| `--from-scratch` | 忽略全部复用，所有节点重跑 |
| `--jobs <N>` | 重试时的并发上限 |
| `--format <FORMAT>` | 结果格式，默认 `text`；`json` 输出重跑与复用集合，以及逐节点原因 |

```powershell
java -jar devpulse\target\devpulse.jar retry 1e6183ab-90ff-4e5b-ab97-4b774155a2d2 --dry-run --format json
```

成功返回 `0`；运行 id 不存在或父运行目标已不可解析返回 `12`；父运行仍在执行、缺少清单，或本阶段未传 `--dry-run` 返回 `13`；读取记录失败返回 `40`。

**复用不等于结果仍然有效**：`retry` 只校验节点定义与执行环境，不检测工作区文件变化。

### `review`：执行前审阅计划

`review [TARGET]` 复用 `pulse` 的完整配置校验和依赖补齐规则，默认目标为 `dev`，但不探测 Bash、不执行任务、不创建运行记录。

| 参数 | 作用 |
|------|------|
| `TARGET` | 任务或流水线名称，默认 `dev` |
| `--directory <目录>` | 项目配置目录，默认当前目录 |
| `--jobs <N>` | 覆盖审阅时显示的并发上限，不修改配置 |
| `--plain` | 强制输出纯文本计划，不打开 TUI |
| `--format <FORMAT>` | 结果格式，默认 `text`；`json` 输出与 `devpulse run --dry-run --format json` 相同的计划文档，且不打开 TUI |

```powershell
java -jar devpulse\target\devpulse.jar review
java -jar devpulse\target\devpulse.jar review build --plain
```

交互终端中显示分层 DAG、依赖深度、前置和后继连线及完整命令，长驻进程还显示就绪命令、超时、用户管理生命周期和停止配置；`Tab` 可切换 DAG 和任务列表。上／下方向键选择任务，`Home`／`End` 选择首／末任务，左／右方向键水平平移图，`w`／`s` 垂直平移，`r` 定位到选中任务，`PageUp`／`PageDown` 滚动详情，`q` 退出。窗口小于 `60 × 16` 时提示放大，退出时恢复终端。输出重定向或无可用交互终端时自动使用纯文本计划。`review --plain` 与 `devpulse run --dry-run` 也显示所有生命周期命令，但不执行检查或清理。

成功返回 `0`。配置整体无效返回 `10`，依赖图或字段值错误返回 `11`，目标不存在或歧义返回 `12`，界面或本地基础设施故障返回 `40`，中断返回 `130`。拓扑顺序和依赖深度不是固定执行批次；静态校验通过不代表命令安全或工具可用。

### `status`：查询运行状态和日志

每次实际执行 `pulse` 都生成独立运行 ID，运行与任务状态写入项目的 `.devpulse\state.mv.db`，运行锁、清单和任务日志保存在 `.devpulse\runs\<run-id>` 下。`status` 默认查看开始时间最新的运行，显示整体及各任务的状态、退出码、错误、UTC 起止时间和耗时；长驻进程按实例展示 ID、尝试编号、PID 和实时状态，包含旧启动尝试，不覆盖历史工作流结果。

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 项目目录，默认当前目录；记录读取不要求配置文件，长驻进程就绪检查使用已记录的 Bash |
| `--run <ID>` | 查看指定历史运行，默认选择最新运行 |
| `--list` | 按开始时间从新到旧列出全部运行 |
| `--log <任务名>` | 输出选中运行的任务日志，可与 `--run` 配合 |
| `--format <FORMAT>` | 结果格式，默认 `text`；`json` 为默认视图、`--run`、`--list`、`--log` 各输出一个 JSON 文档 |
| `--tail <N>` | 配合 `--log`，只保留最后 N 行；`0` 表示全部。文本模式默认全部，JSON 模式默认 `200` |
| `--grep <正则>` | 配合 `--log`，只保留匹配该大小写敏感正则的行；用 `(?i)` 开启忽略大小写 |

```powershell
java -jar devpulse\target\devpulse.jar status
java -jar devpulse\target\devpulse.jar status --list
java -jar devpulse\target\devpulse.jar status --run "<run-id>" --log build
```

`--list` 不能与 `--run` 或 `--log` 混用。查询成功返回 `0`，即使历史任务失败；没有运行记录时也返回 `0`。读取或恢复失败返回 `40`，`--run` 指定的运行无法使用时返回 `12`，参数冲突返回 `2`。

任务历史状态区分 `PENDING`、`RUNNING`、`READY`、`SUCCESS`、`FAILED`、`SKIPPED`、`CANCELLED` 和 `INTERRUPTED`。`READY` 表示长驻进程在执行时达到就绪，不代表后来始终存活。读取遗留的未完成记录时，通过运行锁及执行器身份判断是否已退出；确认退出后保存中断状态，无法确认时保留记录并警告，不自动续跑或接管进程。

详情查询单独显示长驻进程的实时状态和 PID：`STARTING`、`READY`、`NOT_READY`、`EXITED`、`STOPPED` 或 `UNKNOWN`。仅在进程身份匹配时执行保存的就绪检查，Bash 不可用或身份无法确认时显示 `UNKNOWN`。整体运行状态仍表示该次流水线的结果，不因 CLI 返回后的变化追溯改写。`--list` 和 `--log` 只读取历史或日志，不执行就绪检查。

时间以 UTC 显示到秒，耗时自动使用 `ms`、`s`、`m` 或 `h`；运行中标注 `(running)`，取消或中断的估计耗时以 `~` 标记。未启动或无法计算的时间显示 `-`。

历史和日志不自动清理，`.devpulse\` 应排除在版本控制之外；任务输出可能包含敏感信息，共享前必须审核。

### 环境检查（`doctor`）

`doctor` 回答“这台机器能不能跑这个项目”：找到哪个 Bash，以及固定清单里的工具是否在 PATH 上。它只做可用性检查，不执行版本命令，也不检查守护进程健康——找到 `docker` 不代表 Docker 服务正在运行。

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 用于识别项目标记文件的目录，默认当前目录 |
| `--bash <路径>` | 显式指定 Bash，跳过自动发现 |
| `--format <FORMAT>` | 结果格式，默认 `text` 或 `json` |

```powershell
java -jar devpulse\target\devpulse.jar doctor
```

成功返回 `0`；Bash 不可用或目录不存在返回 `20`，盘点中断返回 `130`。

### 配置与执行工作流

1. 运行 `init`，生成 `.devpulse.yaml` 模板。
2. 按项目实际情况修改任务命令与依赖。
3. 运行 `review` 或 `devpulse run --dry-run`，检查依赖闭包和完整命令。
4. 确认后运行 `pulse`，再用 `status` 查询结果或日志。

devpulse 不内置 AI 调用；是否借助外部 AI 编写配置由你决定，但生成的内容必须先审阅，再执行。

### 配置示例（`.devpulse.yaml`）

任务配置仅采用 YAML。以下示例与 `init` 模板一致，可由 `pulse` 执行，但仅打印占位消息，并非已经发现的项目任务：

```yaml
version: 1
parallelism: 4

tasks:
  check:
    cmd: "echo 'devpulse: replace with a project check'"
  build:
    cmd: "echo 'devpulse: replace with a project build'"
    needs: [check]

pipelines:
  dev:
    description: "示例检查与构建 / Example check and build"
    tasks: [check, build]
```

`tasks` 是以任务名为键的映射，每个任务包含必填 `cmd`、可选 `description` 和列表形式的 `needs`。`pipelines` 将目标任务组成流水线，自动补齐依赖；声明顺序不代表串行顺序。`parallelism` 描述并发上限，省略时为 `4`；`version` 省略时为 `1`，`pipelines` 可省略以直接执行单任务。配置命令的执行约定是非登录、非交互 Bash；Windows 使用 Git Bash，不依赖用户的 alias 或启动文件。实际执行前必须审核命令，devpulse 不是沙箱。

多行命令使用 `cmd: |`。字符串含 `: `、`#` 等 YAML 特殊内容时应加引号；Windows 路径优先使用 YAML 单引号并另行遵守 Bash 引号规则。时长使用 `500ms`、`30s` 等字符串，布尔值使用 `true` 或 `false`。

只接受单个 UTF-8 YAML 映射文档，最大 1 MiB、集合嵌套最多 64 层。重复键、非字符串键、未知字段、未知依赖和循环依赖均报错；不支持 include、自定义标签、锚点、别名或合并键。不进行配置变量替换，`${...}` 保持原样交给命令中的 Bash 处理，不读取系统属性或环境变量来填充配置。

### 从 HOCON 迁移

旧 `.devpulse.conf` 不再读取，不会自动转换。仅存在旧文件时会提示迁移；两种文件并存时只读取 `.devpulse.yaml`，旧文件内容被忽略。不要只改扩展名：

1. 将 `tools = [{ name = build, cmd = "..." }]` 改为 `tasks` 映射，以 `build` 为键，移除 `name` 字段；将 HOCON 的 `=`、花括号结构改为 YAML 冒号和缩进。
2. 保留任务名称、命令、`needs`、`pipelines` 和长驻进程选项的含义；任务和流水线引用仍使用列表。HOCON 三引号脚本改为 `cmd: |`。
3. 展开原有 include，移除根级辅助字段，并显式填写原来通过 HOCON 引用得到的值。配置不再解析 `${...}`；需要 Shell 变量时，在命令中按 Bash 规则使用。
4. 保存为 `.devpulse.yaml`，先运行 `review --plain` 或 `devpulse run --dry-run`，核对完整命令与依赖后再执行。`init` 只生成新模板，不迁移旧文件。

Jira 的 HOCON 配置、模板以及 `.devpulse` 下的历史与长驻进程元数据不变；历史查询和清理仍可使用既有记录。

## English

[Project home](../../readme.MD) | [Architecture](pulse-commands-architecture.md)

Pulse reads YAML task configuration and executes batches and local daemons through the shared Bash runtime. The unified `devpulse` entry point provides these commands; Jira is not among them.

### Requirements and build

Building requires JDK 21+, Maven, and Bash (Git Bash on Windows); running the JAR requires Java 21+. Actual task execution and `doctor` require Bash; `init`, `review`, `--dry-run`, history lists, and log reads do not. Readiness and custom stop commands use the Bash path saved at startup.

Run from the repository root:

```powershell
bash build.sh pulse
java -jar devpulse\target\devpulse.jar --help
```

All build and JAR examples below run from the repository root. After installation use `devpulse run [TARGET]` or `devpulse init|review|status|stop|doctor`. See [distribution and installation](../../devpulse-dist/README.md), and the [devpulse CLI](../../devpulse-cli/README.md) for the entry point.

### `init`: create a configuration template

**When to use it**: You know your project's check, build, or other commands and want a configuration skeleton to fill in manually.

`init` creates a UTF-8 `.devpulse.yaml` in the selected directory with a fixed YAML template. It does not scan the project, call AI, install tools, or execute tasks.

The template contains `version: 1`, `parallelism: 4`, two tasks named `check` and `build`, and a `dev` pipeline. `build` declares its dependency on `check` using `needs: [check]`. **The task commands are only message-printing `echo` placeholders, not real checks or builds; replace them with actual project commands.**

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Select the configuration destination; defaults to the current directory, which must already exist |
| `--force` | Replace an existing regular configuration file with the default template; directories and symbolic links are not overwritten |

```powershell
# Create a configuration template in the current directory
java -jar devpulse\target\devpulse.jar init

# Create a configuration template in an existing project directory
java -jar devpulse\target\devpulse.jar init --directory "D:\your-project"

# Overwrite an existing configuration, losing its original contents
java -jar devpulse\target\devpulse.jar init --directory "D:\your-project" --force
```

On success, the command prints the configuration path and returns exit code `0`. A missing directory, an existing configuration without `--force`, or a write failure produces an error and returns `1`.

### `pulse`: run tasks or pipelines

`devpulse run [TARGET]` reads `.devpulse.yaml` from the project directory and defaults to the pipeline or task named `dev`. It includes transitive dependencies automatically and runs shared dependencies and duplicate targets only once. Batch dependencies must exit successfully and daemon dependencies must pass readiness before downstream tasks start. Independent tasks can run concurrently within the limit.

The entire configuration is validated before execution, including field types, unknown fields, duplicate task names, unknown dependencies, and dependency cycles. A target that names both a task and a pipeline is rejected. Configuration errors do not start tasks.

| Option | Effect |
|--------|--------|
| `TARGET` | Task or pipeline name; defaults to `dev` |
| `--directory <directory>` | Project configuration directory; defaults to the current directory |
| `--bash <path>` | Explicitly select Bash; use Git Bash on Windows |
| `--jobs <N>` | Positive concurrency limit overriding configuration `parallelism`, whose built-in default is `4` |
| `--dry-run` | Validate and print the plan and commands without discovering Bash, running tasks, or creating run records |
| `--format <FORMAT>` | Result format; defaults to `text`. With `json`, stdout carries exactly one JSON document and task logs go only to their log files |

```powershell
java -jar devpulse\target\devpulse.jar
java -jar devpulse\target\devpulse.jar build --jobs 2
java -jar devpulse\target\devpulse.jar dev --directory "D:\your-project" --dry-run
```

Tasks run through non-login, non-interactive Bash in the project configuration directory. Batch output and errors are merged, prefixed in the console, and saved as UTF-8 logs. Daemon output is merged and redirected directly to its log, without live console forwarding, so output continues after CLI exit. Bash's exit code determines batch results; an early `exit 0` is not misclassified as failure when the input pipe closes. `errexit` and `pipefail` are not enabled implicitly; set them explicitly when needed.

Ordinary task failures skip descendants while independent branches continue. Daemon faults after readiness cancel running affected descendants and skip unstarted descendants. Logging, startup, or persistence infrastructure faults cancel the run. Successful nodes and last-observed healthy daemons return `0`. Failures return a categorized exit code a program can act on: wholly invalid configuration `10`, dependency-graph or field-value errors `11`, a missing or ambiguous target `12`, a missing environment capability such as Bash `20`, task failure `30`, a daemon that never became ready within its startup limit `31`, a daemon lost after readiness `32`, local infrastructure faults such as run records or logs `40`, and coordinator interruption `130`. A daemon problem outranks an ordinary task failure. With `--format json` the same classification also appears as `code`, `file`, and `line` in the error document, and the run document reports `daemonOutcome` as `NONE`, `NEVER_READY`, or `LOST`. Cancellation attempts batch/visible-descendant cleanup without stopping daemons. Core's `DagRun` handles all scheduling.

Non-interactive batch tasks and explicitly declared local daemons are supported. There is no ordinary batch-task timeout or sandbox. Review commands before execution. The concurrency limit covers batch tasks and daemon startup waits, not already-ready daemons, probe processes, or internal task concurrency.

### Local long-running processes

Declare `type: daemon` to satisfy dependencies through readiness instead of successful exit. Omitting `type` or using `type: task` preserves batch behavior; batch tasks cannot configure daemon fields.

This example assumes an existing Node test server. Adjust commands and the health endpoint to the project; it does not install Node or create server code:

```yaml
version: 1
parallelism: 1
tasks:
  server:
    type: daemon
    cmd: "node server.js"
    ready:
      cmd: "curl --fail --silent --max-time 2 http://127.0.0.1:3000/health"
      interval: 500ms
      timeout: 30s
      checkTimeout: 2s
    stopTimeout: 5s
  check:
    cmd: "node check.js"
    needs: [server]
pipelines:
  dev:
    tasks: [check]
```

| Field | Meaning |
|-------|---------|
| `ready.cmd` | Required; exit code `0` means ready. Use a fast, read-only check |
| `ready.interval` | Check interval, default `500ms` |
| `ready.timeout` | Overall startup and initial readiness wait, default `30s` |
| `ready.checkTimeout` | Individual check timeout, default `2s`, bounded by remaining startup time |
| `stop` | Optional Bash stop command; otherwise terminate identity-confirmed processes and visible descendants |
| `stopTimeout` | Stop-command and exit-wait grace period, default `5s`; followed by best-effort forced termination and up to `2s` confirmation |

After a check or stop command times out, reclaiming that command and its visible descendants may take up to an additional `2s`, outside the configured command timeout.

Daemon commands must remain foreground processes: do not use `&`, `nohup`, or fork/detach themselves. Readiness releases the normal execution slot, so `--jobs 1` can run a check depending on a server. The DAG monitors liveness and readiness during execution. Unexpected exit, including code `0`, failed checks, or timeouts skip unstarted transitive descendants and cancel running affected tasks; independent branches continue. Dependent ready daemons become unavailable and stop being monitored, but are not stopped. Final health checks precede completion. There is no automatic restart, existing-instance takeover, or reuse of occupied ports.

Success, failure, cancellation, and startup check timeouts never stop recorded daemons; users manage them through `stop`. The `shutdownHook` setting is removed and rejected if present. Monitoring ends with the run; there is no separate background scheduler. If initial metadata cannot be reliably recorded, startup attempts to terminate the untracked process. Forced CLI termination, power loss, and detached descendants are not guaranteed to be reclaimed.

Historical task `READY` preserves successful startup/readiness. `status` separately displays recorded health transitions and on-demand live checks. Later daemon faults do not overwrite completed node outcomes, but fail the workflow; historical health describes only its observation time. The roadmap for batch retry/timeout configuration, general DAG persistence/recovery, conditional branches, dynamic graphs, subworkflows, and explicit daemon restart is in the [core usage guide](../../devpulse-core/README.md).

Checks and stop commands receive `DEVPULSE_DAEMON_PID`, `DEVPULSE_DAEMON_INSTANCE_ID`, `DEVPULSE_RUN_ID`, and `DEVPULSE_TASK_NAME`. Stop commands must target only the corresponding instance; do not kill processes by name or automatically delete database directories/volumes. Failed or timed-out stop commands still trigger best-effort process reclamation and return failure with diagnostics. DAG alone owns monitoring limits, and instance records persist the actual policy. Explicit startup retries retain earlier identities, independent scripts, and manual-stop access; task logs append.

### `stop`: manually clean up daemons

```powershell
# Stop all daemons in the latest run
java -jar devpulse\target\devpulse.jar stop

# Stop every instance of the server task in a selected run
java -jar devpulse\target\devpulse.jar stop server --run "<run-id>"

# Stop only one server instance (obtain its ID from status)
java -jar devpulse\target\devpulse.jar stop server --run "<run-id>" --instance "<instance-id>"

# Stop a selected run in another project
java -jar devpulse\target\devpulse.jar stop --directory "D:\your-project" --run "<run-id>"
```

`stop [TASK]` accepts `--directory` and `--run`, defaulting to the latest run. Task-level stopping handles every recorded instance of that task; omission processes all tasks in reverse dependency order. `--instance <ID>` requires a task name and targets only an instance belonging to that task. Recorded stop settings are used without requiring the current `.devpulse.yaml`, cleaning other runs, or deleting logs/business data. PID/start time confirm identity; uncertain identity is refused. A failed instance cleanup does not prevent processing other instances and returns diagnostics. Legacy single-instance records remain queryable and stoppable.

Repeated cleanup of exited instances returns `0`, as do empty history or a selected run without daemons. A requested task without a recorded process, failed stop command, or unconfirmed cleanup returns `1`; interruption returns `130`. Manually stopping a daemon still used by an executing DAG triggers affected-branch cancellation and skipping.

### `retry`: inspect the retry plan for a failed run

`retry` reads the run's `plan.conf` manifest and uses **per-node execution fingerprints** to decide what must rerun: nodes that failed in the parent, nodes whose fingerprint changed, daemon nodes (adoption is not implemented yet), and every descendant of those. All remaining nodes are reused.

This stage **reports only and executes nothing**: `--dry-run` is required, and omitting it returns exit code `13`.

| Option | Effect |
|--------|--------|
| `RUN_ID` | The run to retry |
| `--directory <directory>` | Project configuration directory; defaults to the current directory |
| `--dry-run` | Report the rerun and reuse sets; required in this stage |
| `--force` | Allow a plan for a run that is still executing |
| `--from-scratch` | Ignore all reuse and rerun every node |
| `--jobs <N>` | Concurrency limit for the retry |
| `--format <FORMAT>` | Result format; defaults to `text`. `json` reports both sets plus a per-node reason |

```powershell
java -jar devpulse\target\devpulse.jar retry 1e6183ab-90ff-4e5b-ab97-4b774155a2d2 --dry-run --format json
```

Success returns `0`; an unknown run id or a parent target that no longer resolves returns `12`; a run that is still executing, a missing manifest, or a missing `--dry-run` returns `13`; a failure to read the record returns `40`.

**Reuse is not a claim that the result is still valid**: `retry` verifies node definitions and the execution environment, not working-tree changes.

### `review`: review plans before execution

`review [TARGET]` shares `pulse`'s full configuration validation and dependency completion rules, defaulting to `dev`, but does not discover Bash, execute tasks, or create run records.

| Option | Effect |
|--------|--------|
| `TARGET` | Task or pipeline name; defaults to `dev` |
| `--directory <directory>` | Project configuration directory; defaults to the current directory |
| `--jobs <N>` | Override the displayed concurrency limit without modifying configuration |
| `--plain` | Force a plain-text plan instead of opening the TUI |
| `--format <FORMAT>` | Result format; defaults to `text`. `json` emits the same plan document as `devpulse run --dry-run --format json` and never opens the TUI |

```powershell
java -jar devpulse\target\devpulse.jar review
java -jar devpulse\target\devpulse.jar review build --plain
```

An interactive terminal shows a layered DAG, dependency depth, prerequisite/dependent connections, and complete commands. Daemons also show readiness commands, timeouts, user-managed lifecycle, and stop settings. `Tab` switches between graph and task list. Up/Down selects tasks, `Home`/`End` selects the first/last task, Left/Right pans horizontally, `w`/`s` pans vertically, `r` centers selection, `PageUp`/`PageDown` scrolls details, and `q` exits. Windows smaller than `60 × 16` show an enlargement notice, and exit restores the terminal. Redirected or unavailable interactive output uses a plain-text plan. `review --plain` and `devpulse run --dry-run` also show all lifecycle commands without running checks or cleanup.

Success returns `0`. Wholly invalid configuration returns `10`, dependency-graph or field-value errors return `11`, a missing or ambiguous target returns `12`, interface or local infrastructure faults return `40`, and interruption returns `130`. Topological order and dependency depth are not fixed execution batches; valid configuration does not guarantee command safety or tool availability.

### `status`: query run status and logs

Every actual `pulse` execution creates an independent run ID, storing run and task state in the project's `.devpulse\state.mv.db` and a run lock, manifest, and task logs under `.devpulse\runs\<run-id>`. By default, `status` shows the run with the latest start time, including overall and task states, exit codes, errors, UTC start/finish times, and durations. Daemons are shown per instance with ID, attempt, PID, and live state, including earlier startups without overwriting historical workflow outcomes.

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Project directory; defaults to current directory. Reading records needs no configuration; daemon readiness uses recorded Bash |
| `--run <ID>` | Select a historical run instead of the latest |
| `--list` | List all runs by start time, newest first |
| `--log <task>` | Print a task log from the selected run; can be combined with `--run` |
| `--format <FORMAT>` | Result format; defaults to `text`. `json` emits one document per view for the default view, `--run`, `--list`, or `--log` |
| `--tail <N>` | With `--log`, keep only the last N lines; `0` keeps all. Text mode defaults to all, JSON mode to `200` |
| `--grep <regex>` | With `--log`, keep lines matching this case-sensitive regular expression; use `(?i)` for case-insensitive matching |

```powershell
java -jar devpulse\target\devpulse.jar status
java -jar devpulse\target\devpulse.jar status --list
java -jar devpulse\target\devpulse.jar status --run "<run-id>" --log build
```

`--list` cannot be combined with `--run` or `--log`. Successful queries return `0`, even for failed historical tasks or an empty history. Reading or recovery failures return `40`, an unusable `--run` identifier returns `12`, and conflicting options return `2`.

Historical task states distinguish `PENDING`, `RUNNING`, `READY`, `SUCCESS`, `FAILED`, `SKIPPED`, `CANCELLED`, and `INTERRUPTED`. `READY` records that a daemon became ready during execution, not that it remains alive later. Reading unfinished records checks the run lock and executor identity. Confirmed exit persists an interrupted result; uncertainty preserves the record and prints a warning. There is no automatic resume or process takeover.

Detailed queries separately show live daemon state and PID: `STARTING`, `READY`, `NOT_READY`, `EXITED`, `STOPPED`, or `UNKNOWN`. Recorded checks run only for matching process identities; unavailable Bash or unconfirmed identity yields `UNKNOWN`. The overall run state remains the pipeline outcome and is not retroactively changed after CLI exit. `--list` and `--log` read only history/logs without readiness probes.

Times are displayed in UTC to whole seconds, with durations using `ms`, `s`, `m`, or `h` automatically. Active runs show `(running)`; estimated durations for cancellation or interruption are marked `~`. Unstarted or unavailable times display `-`.

History and logs are not automatically cleaned up; exclude `.devpulse\` from version control and review task output for sensitive information before sharing.

### Environment check (`doctor`)

`doctor` answers "can this machine run this project": which Bash was found, and whether the fixed tool list is on the path. It reports availability only — it runs no version commands and no daemon health checks, so finding `docker` does not establish that its daemon is running.

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Directory used to detect project markers; defaults to the current directory |
| `--bash <path>` | Explicit Bash path, skipping discovery |
| `--format <FORMAT>` | Result format: `text` (default) or `json` |

```powershell
java -jar devpulse\target\devpulse.jar doctor
```

It returns `0` on success, `20` when Bash is unavailable or the directory is missing, and `130` when the check is interrupted.

### Configuration and execution workflow

1. Run `init` to generate a `.devpulse.yaml` template.
2. Edit the task commands and dependencies for your project.
3. Run `review` or `devpulse run --dry-run` to inspect the dependency closure and complete commands.
4. After approval, run `pulse`, then use `status` to query results or logs.

devpulse makes no AI calls; whether you use an external AI to draft configuration is your decision, but review what it produces before executing it.

### Configuration example (`.devpulse.yaml`)

Task configuration uses YAML only. The following example matches the `init` template and can be executed by `pulse`, but only prints placeholder messages rather than running discovered project tasks:

```yaml
version: 1
parallelism: 4

tasks:
  check:
    cmd: "echo 'devpulse: replace with a project check'"
  build:
    cmd: "echo 'devpulse: replace with a project build'"
    needs: [check]

pipelines:
  dev:
    description: "示例检查与构建 / Example check and build"
    tasks: [check, build]
```

`tasks` is a mapping keyed by task name; each task has required `cmd`, optional `description`, and a `needs` list. `pipelines` groups target tasks with automatic dependency completion; declaration order does not imply sequential execution. `parallelism` is the concurrency limit, defaulting to `4`; `version` defaults to `1`, and `pipelines` may be omitted to execute tasks directly. Commands run through non-login, non-interactive Bash; Windows uses Git Bash, without depending on user aliases or startup files. Review commands before actual execution; devpulse is not a sandbox.

Use `cmd: |` for multiline commands. Quote strings containing YAML-special content such as `: ` or `#`; prefer YAML single quotes for Windows paths and separately follow Bash quoting rules. Durations are strings such as `500ms` and `30s`; booleans use `true` or `false`.

Only a single UTF-8 YAML mapping document is accepted, limited to 1 MiB and 64 collection nesting levels. Duplicate keys, non-string keys, unknown fields/dependencies, and cycles fail validation. Includes, custom tags, anchors, aliases, and merge keys are unsupported. There is no configuration substitution: `${...}` remains literal for Bash inside commands, with no system properties or environment variables filling configuration values.

### Migrating from HOCON

Legacy `.devpulse.conf` files are no longer read or automatically converted. A legacy-only project receives migration guidance; when both files exist, only `.devpulse.yaml` is read and the legacy content is ignored. Do not just rename the extension:

1. Replace `tools = [{ name = build, cmd = "..." }]` with a `tasks` mapping keyed by `build`, removing `name`; replace HOCON `=` and braces with YAML colons and indentation.
2. Preserve task names, commands, `needs`, `pipelines`, and daemon option meanings; task and pipeline references remain lists. Replace HOCON triple-quoted scripts with `cmd: |`.
3. Inline former includes, remove root-level helper fields, and explicitly supply values formerly obtained through HOCON substitutions. `${...}` is no longer resolved by configuration loading; use Bash variables inside commands when appropriate.
4. Save as `.devpulse.yaml`, run `review --plain` or `devpulse run --dry-run`, and verify complete commands and dependencies before execution. `init` creates a new template rather than migrating existing files.

Jira HOCON configuration/templates and history/daemon metadata under `.devpulse` remain unchanged; existing records remain available for history queries and cleanup.
