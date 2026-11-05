# devpulse 架构与实现设计 / Architecture and implementation design

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

本项目旨在构建一个轻量、高效、具备 AI 协同能力的本地 DevOps 智能调度工具箱。

技术栈、语言使用原则、构建打包、资源管理和测试约束统一维护在 [Copilot 仓库指令](../.github/copilot-instructions.md) 中；本文描述功能行为、核心架构与开发路线图。

### 一、配置格式与加载

- 任务配置仅支持 UTF-8 YAML 文件 `.devpulse.yaml`，不再读取 `.devpulse.conf`；迁移说明见[核心 CLI 使用指南](core-cli.md)。
- 使用 SnakeYAML Engine 的 YAML 1.2 Core Schema 解析单个映射文档，限制文件大小为 1 MiB、集合嵌套为 64 层；拒绝重复键、非字符串键、自定义标签、锚点、别名和合并键，不构造任意 Java 对象。
- 将 YAML 节点转换为带文件和行号来源的 Typesafe Config 值，复用类型化读取和时长校验；不将 YAML 字符串再次按 HOCON 解析。
- 默认 `version: 1`、`parallelism: 4` 和空 `pipelines`；不做配置变量替换，不加载 JVM 系统属性或环境变量，命令中的变量保留给 Bash。
- 不支持 include 或远程资源加载；复杂逻辑由用户维护的本地脚本承载。
- `tasks` 是以任务名为键的映射，任务支持可选 `description`；`pipelines` 通过目标任务列表选择执行范围。声明顺序不代表依赖顺序。
- 配置错误应报告文件来源及可用的行号信息；字段类型、重复键、未知依赖和循环依赖均需在执行任何命令之前校验。根级、任务、就绪检查和流水线的未知字段均报错。
- `gather` 生成的 Prompt 与 `init` 生成的默认配置统一使用 YAML 示例。Jira 配置和内部运行记录继续使用 HOCON，不受任务配置迁移影响。

### 二、核心架构设计

系统包含命令入口、资产盘点模块、可复用的 Bash 基础组件、调度引擎、本地长驻进程管理模块、配置初始化模块与执行计划审阅模块，职责清晰，方便后续解耦和扩展：

```text
用户终端输入 -> 各独立工具入口（或组合兼容入口） -> 对应命令实现
```

#### 1. 工具入口（`PulseCLI.java`、`GatherCLI.java`、`JiraCommand.java`）

**职责**：各自解析终端参数并执行所属工具命令；`devpulse-cli` 中的 `DevPulseCLI.java` 仅保留旧组合入口的参数分发。

#### 2. 资产自动盘点模块（`GatherCommand.java`）

**职责**：收集本地系统的客观环境事实，按所选语言组装 AI 提示词（默认中英文对照），输出到终端并默认复制到剪贴板。

**实现步骤**：

- **环境与工具盘点**：记录 OS、架构、Java 版本、Bash 路径，通过 Bash 的 `command -v` 检查固定工具清单，不执行工具版本或健康检查。
- **项目特征嗅探**：仅检查项目根目录中关键指纹是否存在（如 `package.json`、`pom.xml`、`Dockerfile`、`.git`），不读取文件内容、不递归扫描目录。
- **采集边界**：环境变量仅用于 Bash 定位及工具查找，不将环境变量值或凭据加入提示词。提示词包含项目路径和工具位置，用户发送给外部 AI 前应审核。
- **提示词输出**：生成包含本地事实、YAML 示例、Bash 执行约束和命令审核提醒的 Markdown；支持中文、英文或中英双语，双语模式保留同页面语言导航，单语言模式仅包含对应语言章节，不显示双语导航。本地事实中的路径、工具名等数据及 YAML 示例保持原样；信息不足时要求 AI 声明假设，不捏造任务。
- **编码警告**：提示词输出前检查 Java 控制台的 `Console.charset()`；无控制台时检查 `System.out.charset()`。检测到非 UTF-8 时向标准错误输出英文警告，包含检测到的编码和 Git Bash 的 `Options > Text > Character set > UTF-8` 设置提示，不改变语言、输出编码、剪贴板内容或成功退出码。该检查反映 Java 检测到的编码，不能可靠读取 Git Bash 窗口的实际显示字符集；重定向输出时也可能触发，不代表已经确认发生乱码。
- **剪贴板控制**：默认通过 AWT 写入剪贴板；无图形环境、剪贴板忙或不可访问时提示警告，保留标准输出，盘点仍成功。
- **命令选项**：`gather --directory <目录>` 指定盘点位置，`--bash <路径>` 指定 Bash，`--no-clipboard` 禁用剪贴板复制；`--language zh|en|both` 同时控制终端与剪贴板中的提示词语言，默认 `both`，不支持的取值在盘点前拒绝。目录无效、Bash 缺失或扫描失败时返回非零退出码。遇到中文终端显示乱码时可使用 `gather --language en` 避开提示词中的中文；该选项不修复终端编码，也不翻译本地事实数据。

#### 3. Bash 基础组件（`BashShell.java`）

- **统一解释器**：所有 OS 均使用 Bash，Windows 使用 Git Bash；找不到可用 Bash 时明确报错，不回退到 `sh`、CMD、PowerShell 或 WSL。
- **定位顺序**：显式 `--bash` 路径优先，其次探测 PATH；Windows 额外检查 Git 安装位置，自动探测只接受具有 Git 安装标记的 Bash，避免误选 WSL 入口。
- **可用性验证**：通过短时子进程验证 Bash 能实际启动，而不是仅检查文件存在。
- **启动方式**：使用 `ProcessBuilder` 启动 `bash --noprofile --norc -s --`，普通任务通过标准输入写入完整 UTF-8 脚本后关闭输入；长驻进程、就绪和停止命令通过保存的脚本文件重定向标准输入。不自行按空格拆分，也不将命令文本放入 `-c` 参数，避免 Windows 命令行转义改变引号和变量表达式；不提供交互输入。
- **环境与目录**：使用 `ProcessBuilder.directory()` 设置工作目录，继承父进程环境，但移除 `BASH_ENV`、`ENV` 和 `BASH_FUNC_*`，避免自动执行环境指定的启动文件或导入用户函数。不依赖 alias 或用户启动脚本。
- **探测资源控制**：Bash 验证与环境扫描均设置超时，持续读取扫描输出，并清理探测进程。
- **平台边界**：统一 Bash 语法不代表工具、路径、文件权限和操作系统能力完全一致；Git Bash 不是完整 Linux 环境。

#### 4. 图拓扑调度引擎（`WorkflowRun.java`、`PulseCommand.java`）

**职责**：读取用户存放的 `.devpulse.yaml` 配置文件，智能计算依赖顺序，拉起本地系统进程执行。

**独立计划模块**：`devpulse-core` 的不可变 `TaskDefinition`、`WorkflowDefinition` 和 `TaskPlan` 只依赖 JDK，不依赖 YAML、Typesafe Config 或 CLI。任务定义包含命令、依赖和可选的长驻进程选项；工作流定义包含任务列表与流水线目标。`TaskPlan.create` 校验完整定义，再为指定任务或流水线补齐传递依赖并生成拓扑有序的计划；不会读取配置、探测 Bash、执行命令或写入运行记录。

```java
import io.github.devpulse.core.execution.TaskDefinition;
import io.github.devpulse.core.execution.TaskPlan;
import io.github.devpulse.core.execution.WorkflowDefinition;
import java.util.List;
import java.util.Map;

var workflow = new WorkflowDefinition(
        List.of(
                new TaskDefinition("check", "echo check", List.of()),
                new TaskDefinition("build", "echo build", List.of("check"))),
        Map.of("dev", List.of("build")));
var plan = TaskPlan.create(workflow, "dev");
```

`PlanningException` 通过 `scope()`、`name()` 和 `field()` 提供与配置格式无关的错误定位；Pulse 的 `PulseConfiguration.plan` 将其关联到原 YAML 文件和可用行号。长驻进程的终端说明由 Pulse 的 `TaskDetails` 生成，不进入核心定义。

**独立执行运行时**：core 的 `WorkflowRun` 绑定一个计划和并发上限，阻塞式 `execute()` 只能调用一次，返回整体状态及成功、失败、跳过数量。`cancel()` 可从其他线程调用；执行前取消不会启动任务，重复取消和完成后取消不改变已结束的结果，执行中的外部取消会中断执行线程。调用方用 try-with-resources 管理 `RunHistory.Writer`，并自行决定是否注册 JVM 退出钩子；core 不注册全局钩子。Pulse 将成功结果映射为退出码 `0`，其他结果映射为 `1`，中断映射为 `130`。

`RunHistory` 和 `DaemonManager` 也位于 core，保留原有调度、失败策略与版本 `1` 的 HOCON 记录格式。执行运行时依赖 Typesafe Config 读写这些记录，但不读取任务 YAML、不依赖 picocli 或 TUI。嵌入调用示例（接续上面的 `plan`）：

```java
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.execution.RunHistory;
import io.github.devpulse.core.execution.WorkflowRun;
import java.io.PrintWriter;
import java.nio.file.Path;

var directory = Path.of(".").toAbsolutePath().normalize();
var shell = BashShell.discover(null);
try (var history = new RunHistory(directory).begin("dev", plan, 4)) {
    var run = new WorkflowRun(shell, directory, new PrintWriter(System.out),
            history, plan, 4);
    var result = run.execute();
}
```

**实现步骤**：

- **解析配置**：按配置加载规则读取 YAML 配置，构造配置模型。
- **目标选择**：`pulse` 默认选择 `dev`；`pulse <名称>` 支持流水线或单任务。目标同时是任务名和流水线名时拒绝执行，要求用户消除重名。
- **构建有向无环图（DAG）**：将 `tasks` 映射中的每个任务视作一个节点（Vertex），将它们之间的 `needs` 依赖关系视作有向边（Edge）。
- **依赖补齐**：流水线的 `tasks` 声明最终目标，自动补齐全部传递依赖；共享依赖和重复目标在本次执行中只运行一次。列表顺序不代表依赖顺序。
- **拓扑排序与循环依赖检测**：采用 Kahn 算法或 DFS 算法检查依赖图。如果发现循环依赖（例如 A 依赖 B，B 又依赖 A），在启动任何命令前报错；执行时仅将所有依赖均已满足的任务加入就绪队列：普通任务成功退出，长驻进程通过就绪检查；在并发上限内调度。
- **底层进程控制器**：复用 Bash 基础组件拉起本地命令，工作目录为项目配置所在目录。合并错误流和标准流（`redirectErrorStream(true)`），普通任务以 UTF-8 持续读取并输出带任务名前缀的日志，长驻进程直接写入日志文件。工具必须已安装且能被 Bash 找到；执行权限与当前用户相同，不提供沙箱。
- **并发上限**：默认最多同时运行 4 个批任务或长驻进程启动等待；支持配置 `parallelism` 与 `pulse --jobs` 覆盖，优先级为命令行参数高于项目配置、高于默认值。取值必须为正整数，并在执行前校验；该限制不控制已就绪长驻进程数量、探测进程或外部任务内部的并发。
- **失败处理**：以 Bash 退出码判断任务结果；`0` 成功，非零失败。长脚本提前成功退出时，允许输入管道因 Bash 关闭标准输入而产生的写入 I/O 异常，不误判为失败；日志读取和持久化失败仍需报错。不隐式启用 `errexit` 或 `pipefail`，需要时由任务命令显式设置。观察到失败后停止启动新任务，等待所有已启动任务结束，返回非零退出码并汇总成功、失败和跳过数量。
- **命令选项**：`--directory` 指定项目目录，`--bash` 指定 Bash，`--jobs` 覆盖并发上限；`--dry-run` 校验完整配置并显示补齐依赖后的计划及命令，不探测 Bash、不启动任务。
- **执行边界**：支持非交互批任务与显式配置的本地长驻进程，不提供普通批任务超时、自动重启或常驻调度平台。取消时尽力终止批任务及可见后代；长驻进程是否清理由 `shutdownHook` 控制。不保证回收已脱离进程树的后台进程。

#### 5. 配置初始化模块（`InitCommand.java`）

**职责**：在项目目录生成可修改的 `.devpulse.yaml` 配置骨架，不扫描环境、不调用 AI、不执行任务，也不要求已安装 Bash。

- **默认配置**：UTF-8 编码的 YAML，包含 `version: 1`、`parallelism: 4`、两个仅使用 Bash `echo` 的示例任务，以及带有依赖关系的 `dev` 流水线。
- **目录选择**：默认使用当前工作目录；`init --directory <目录>` 指定其他已存在的项目目录，不自动创建目录。
- **覆盖保护**：默认仅创建新文件，配置已存在时拒绝写入并返回非零退出码；仅显式 `--force` 时允许覆盖普通文件，不跟随配置文件的符号链接。
- **结果反馈**：成功后打印配置路径和命令审核提醒；目录无效或文件写入失败时明确报错，不执行示例任务。

#### 6. 本地任务执行状态持久化（`RunHistory.java`）

**方案**：采用项目内文件持久化，不引入数据库。`pulse` 在启动任务前保存计划，持续保存任务状态与日志，支持多个独立运行同时执行。

```text
.devpulse\
  runs\
    <run-id>\
      state.conf
      run.lock
      logs\
        <task-id>.log
      daemons\
        <task-id>.conf
        <task-id>-start.sh
        <task-id>-ready.sh
        <task-id>-stop.sh
        <task-id>.lock
```

- **存储布局**：每次实际执行生成唯一 `run-id`，在项目配置所在目录的 `.devpulse\runs` 下创建独立运行目录，避免多次或并发运行相互覆盖。使用生成的 `task-id` 作为日志文件名，不直接将任务名作为路径。`--dry-run` 不创建运行记录。
- **状态格式**：`state.conf` 使用 UTF-8 HOCON，复用 Typesafe Config 读写。记录格式版本、运行 ID、目标、项目目录、实际并发上限、运行状态与起止时间，以及各任务的 ID、名称、依赖、状态、起止时间、退出码、失败原因和相对日志路径。尚未产生的结束时间、退出码等字段不写入。
- **状态语义**：任务状态区分 `PENDING`、`RUNNING`、`READY`、`SUCCESS`、`FAILED`、`SKIPPED`、`CANCELLED`、`INTERRUPTED`；`READY` 记录长驻进程在该次执行中通过就绪检查，并非最终退出结果。正常失败为 `FAILED`，因失败策略未启动的任务为 `SKIPPED`，主动取消导致终止的批任务为 `CANCELLED`，执行器异常退出后无法确认结果的任务为 `INTERRUPTED`。整体运行状态表示流水线结果，与长驻进程后续的实时状态分开。
- **写入时机与一致性**：执行前保存初始计划，启动任务前持久化 `RUNNING`，任务结束后保存结果，运行结束时写入整体结果；主动取消时尽力保存取消状态。同次运行的状态写入串行化，先写同目录临时文件，再通过原子替换更新 `state.conf`。文件系统不支持原子替换时明确报错，不退化为直接覆盖。初始化失败时不启动任务；执行中的持久化失败应明确报错、停止启动新任务、等待已启动任务结束并返回非零退出码。
- **日志落盘**：普通任务持续读取合并后的标准输出和错误输出，在保留控制台日志的同时写入 UTF-8 日志；长驻进程直接重定向合并输出到文件，不依赖 CLI 的日志读取线程，也不实时转发到控制台。日志独立于状态快照写入，不因每行输出重写 `state.conf`；异常退出可能丢失尚未刷新的尾部，长驻程序本身需使用适当的输出编码和刷新策略。
- **异常退出识别**：记录执行器 PID 和进程启动时间（可获取时），并在执行期间持有运行目录内的独占文件锁；PID 本身不足以确认进程身份。后续状态读取若发现未结束的运行，应先核验文件锁及执行器身份，不能将其他仍在执行的运行标为中断。确认原执行器已退出后，将遗留的 `RUNNING` 任务标记为 `INTERRUPTED`、尚未启动的任务标记为 `SKIPPED`，并保存运行中断状态；无法确认时保留原记录并提示状态不确定。
- **并发读取**：先完整读取并关闭状态文件，再解析 HOCON。Windows 读取句柄可能短暂阻止替换，允许最多 200 ms 的原子替换重试；不降级为非原子写入，持续失败仍明确报错。
- **查询方式**：`status` 默认查看开始时间最新的运行，显示整体状态、任务状态、退出码、失败原因，以及运行和每个任务的开始时间、结束时间与耗时；`--run <run-id>` 选择历史运行，`--list` 列出所有运行及其起止时间、耗时，`--log <任务名>` 读取选中运行的任务日志。按开始时间排序，不让较旧运行的晚完成覆盖较新记录；查询成功返回 `0`，不因历史任务失败而返回失败退出码。
- **时间展示**：起止时间使用 UTC ISO 8601，仅显示到秒；持久化时间戳仍保留原始精度。耗时自动选择单位：小于 1 秒显示整数 `ms`，小于 1 分钟显示 `s`，小于 1 小时显示 `m`，其余显示 `h`；秒、分钟和小时最多保留两位小数并去除末尾零，例如 `303ms`、`2.76s`、`1.5m`、`27h`。运行中按查询时刻计算已耗时并标注 `(running)`；未启动任务的起止时间与耗时显示 `-`。取消或中断记录中的结束时间可能是取消或恢复时刻，不能视为实际进程退出时间，其耗时加 `~` 标记为估计值；缺少时间或系统时钟倒退导致无法计算时显示 `-`。耗时由现有时间戳计算，不额外持久化。
- **环境检查**：`status --tools` 保留 Bash 和工具可用性检查，可配合 `--bash`；不执行版本命令或探测 Docker 等守护进程。所有模式支持 `--directory`。工具模式不能与运行查询参数混用。
- **查询与恢复边界**：读取历史、列表和日志不要求 Bash 或项目配置仍然存在；长驻进程的实时就绪检查与自定义停止命令使用启动时保存的 Bash 和脚本，Bash 不可用时不能确认就绪。旧版本未保存的运行无法回填。状态文件不允许 include，也不从环境变量解析额外数据。不自动断点续跑、接管或重启遗留进程；手动清理仅处理已记录且身份可确认的实例。后续若增加恢复执行，需校验命令及依赖是否变化，并明确幂等性与重跑策略。
- **本地数据边界**：运行记录与日志属于生成数据，应将 `.devpulse\` 排除在版本控制之外；不保存环境变量或凭据快照。任务输出可能包含敏感信息，用户应在共享日志前审核。首期不自动清理历史记录；大量历史检索或跨进程事务需求出现后再考虑 SQLite。

#### 7. 执行计划审阅模块（`ReviewCommand.java`、`ReviewTui.java`、`DagWidget.java`）

**职责**：静态校验任务或流水线的执行计划，在交互式 TUI 中供用户执行前审阅，不调用 AI。

- **目标与校验**：`review` 默认选择 `dev`，`review <名称>` 支持流水线或单任务。复用 `PulseConfiguration.load` 和 `PulseConfiguration.plan`，后者调用 core 的 `TaskPlan.create`；与 `pulse` 保持相同的完整配置校验、目标重名检查、循环依赖检测和传递依赖补齐规则。
- **TUI 与依赖展示**：使用 TamboUI `0.6.0`（本次接入时 Maven Central 最新正式版）的 `tamboui-tui` 与 `tamboui-jline3-backend`，版本统一定义在 `pom.xml`，不使用快照版本。左侧默认显示分层 DAG，可切换至按拓扑顺序排列的任务列表；右侧显示选中任务的直接前置依赖、计划内后继任务和完整 Bash 命令。DAG 中的编号对应任务列表顺序，长名称截断后可在详情中查看完整名称。
- **DAG 布局与高亮**：自定义 TamboUI Widget 按最长依赖路径的深度 `L` 从左到右分层，同层按拓扑顺序从上到下排列；共享任务仅绘制一个节点，每条直接依赖均保留指向后继节点的连线。跨层长连线从节点行之间绕过中间节点；交叉线可能共享显示单元格，不代表新增依赖，右侧详情提供精确的直接依赖关系。选中节点为黄色，全部传递前置依赖及其路径为绿色，全部传递后继及其路径为品红色；无关节点与连线保持灰色。不自动消除所有交叉。
- **计划信息与交互**：顶部显示目标、项目目录、任务数量、有效并发上限和颜色说明。上／下方向键选择任务，`Home`／`End` 选择首／末任务；左／右方向键水平平移 DAG，`w`／`s` 垂直平移，`r` 将视图定位到选中任务；`Tab` 切换 DAG 与任务列表，`PageUp`／`PageDown` 滚动右侧详情，`q` 退出。切换任务时自动定位图中节点并重置详情滚动位置；图形按视口裁剪，不覆盖相邻面板，长命令按终端显示宽度换行，控制字符以可见文本展示而不作为终端指令解释。依赖深度和拓扑顺序不代表固定执行批次或串行顺序，实际调度仍由依赖成功情况及并发上限决定。
- **终端生命周期与降级**：通过 JLine 检测标准输出终端；没有可用的交互终端（如重定向或管道）时自动保留纯文本计划，不读取交互按键。`--plain` 显式选择原有 ASCII 依赖列表及命令输出。交互模式使用备用屏幕和原始输入模式，通过 try-with-resources 及 TamboUI 的退出清理恢复屏幕、光标和终端输入状态。终端缩小时重新排版，小于 `60 × 16` 时提示放大窗口且仍允许退出。
- **命令选项**：`--directory` 指定项目配置目录，`--jobs` 仅覆盖审阅时显示的并发上限，不修改配置；与 `pulse` 使用相同的正整数约束与优先级。`--plain` 禁用 TUI。不提供 `--bash`，因为审阅不依赖 Bash。
- **执行边界与反馈**：不探测工具、不启动 Bash、不执行任务、不写入运行记录；有效计划及正常退出返回 `0`，配置、目标或 TUI 初始化／渲染无效时报告错误并返回非零退出码。静态校验成功不代表命令安全或工具可用。保留现有 `pulse --dry-run` 行为。

#### 8. 预定义 CLI 集成：Jira（`JiraCommand.java`）

**模块与接口**：根目录 `pom.xml` 聚合各模块。`devpulse-dependencies` 管理外部依赖，`devpulse-bom` 管理可消费的内部产物版本；构建插件版本在根 POM 的 `pluginManagement`。`devpulse-core` 提供独立工作流定义、计划构建、执行运行时、运行记录和长驻进程管理，以及 Bash 和环境盘点共享代码，不生成可执行 JAR；`devpulse-plugins/pulse` 包含工作流命令 `pulse`、`init`、`review`、`status`、`stop`、YAML 到核心定义的转换、终端展示和 CLI 生命周期适配，`devpulse-plugins/gather` 包含提示词命令，`devpulse-plugins/jira` 独立访问 Jira。各工具 JAR 仅依赖所需模块，不相互依赖；共享库不注册命令，也不引入动态插件加载。`devpulse-cli` 是保留 `devpulse <子命令>` 用法的组合式兼容产物，不加入单工具发行包。`devpulse-dist` 依赖工具产物并组装独立归档，供用户按需安装，不被工具反向依赖。运行模块继承 `devpulse-dependencies` 的本地相对父路径，内部 BOM 不作为运行时依赖。安装和发行细节见 [发行与安装](distribution.md)。

**版本来源**：根 `pom.xml` 的 `<revision>` 是唯一的项目版本值。Maven 3 要求各子模块写出 `<parent><version>`，这里统一引用 `${revision}`，不重复填写版本号；内部模块依赖和 BOM 使用 `${project.version}`。构建继承的 Flatten Maven Plugin 仅解析 CI-friendly 版本占位符，保证以后安装或发布的 POM 可被其他 Maven 项目消费。外部依赖、构建插件、HOCON 配置格式的版本各有独立语义，不跟随项目版本递增。

**Jira 实现**：通过 Java HTTP 客户端访问 Cloud REST v3 或 Data Center REST v2；站点、部署类型和凭据仅从环境变量读取，拒绝远程明文 HTTP，不重定向含授权头的请求。`init` 创建 HOCON 字段模板及同名 Markdown 描述文件；`create`、`issue view`、`export`、`update`、`transitions`、`transition`、`comment`、`comment-export`、`comment-update` 和 `attach` 提供基本的 Issue 管理。导出使用 `/editmeta` 限制字段，保留其原始结构并记录字段基线及描述哈希；Issue 与评论的回写仅上传修改字段，并在上传前检查 `updated`，但不承诺原子冲突控制。Cloud 支持有限且严格的 ADF/Markdown 转换；Data Center 描述及评论暂限纯文本，无法安全转换的富文本拒绝往返。具体选项、安全边界与示例见 [Jira CLI](plugins/jira.md)。

#### 9. 本地长驻进程管理（`DaemonManager.java`、`StopCommand.java`）

- **配置与启动**：任务可声明 `type: daemon`、必填的 `ready.cmd`、检查间隔和超时；`shutdownHook` 默认 false，可选 `stop` 与 `stopTimeout`。启动、检查和停止脚本按任务 ID 保存，进程 PID、启动时间、Bash 路径及停止配置通过独立 HOCON 文件原子持久化。无法记录身份或元数据时尽力终止尚未受管的实例。
- **依赖与并发**：普通依赖等待成功退出，长驻进程依赖等待就绪；就绪后释放执行槽，但继续持有进程及监控。`--jobs` 不限制已就绪进程的总数或探测进程。每个进程通过虚拟线程进行有时限的周期探测；意外退出或失去就绪状态使运行失败并停止启动新任务，不自动重启。
- **生命周期**：运行结束或可处理的退出时，仅清理开启 hook 的实例，按计划逆依赖顺序处理。未开启 hook 的实例在 CLI 返回后继续运行，监控随 CLI 结束；后续 `status` 详情按需核验存活及就绪，不存在独立后台调度器。就绪状态不是跨进程的恢复点。
- **清理与所有权**：`stop [TASK] --run <ID>` 使用已保存配置，默认选最新运行、可省略任务名清理全部实例。单实例清理使用独立锁，与运行状态写入分开；PID 和启动时间不匹配时不终止复用 PID 的其他进程，身份不确定时拒绝操作。停止命令接收实例身份环境变量，先执行有时限的停止，再尽力强制回收已确认的进程及可见后代；不按名称批量清理，不删除业务数据。
- **审阅与诊断**：静态审阅显示全部生命周期命令和时限，不执行探测。`status` 将历史启动结果和实时 `STARTING` / `READY` / `NOT_READY` / `EXITED` / `STOPPED` / `UNKNOWN` 分开显示；历史列表与日志读取不执行检查。日志、元数据与脚本属于本地生成数据，可能包含敏感内容，不能提交或未经审核共享。

### 三、开发落地路线图

首版范围包含 `init`、`pulse` 的并发调度、`gather`、`review` 和 `status`，不将并发或 AI 协同流推迟到后续版本。以下阶段为内部开发顺序，而不是对外版本的功能拆分。

#### 阶段一：建立脚手架与命令行骨架（MVP）

- 按 Copilot 仓库指令建立构建与命令行骨架。
- 确保打包后的可执行 JAR 能通过 `--help` 打印出规范的帮助说明。

#### 阶段二：攻克 gather 模块（打通 AI 协同流）

- 实现可复用的 Bash 探测与调用组件。
- 实现工具查找、项目指纹检测和中英文提示词生成。
- 实现 AWT 剪贴板写入及无图形环境降级，支持仅输出提示词。

#### 阶段三：攻克 pulse 模块（打通调度流）

- 使用 SnakeYAML Engine 解析 YAML 任务配置，保留来源行号并复用 Typesafe Config 完成默认值合并与执行前校验。
- 手写基于 Kahn 的拓扑排序函数，确保能够处理复杂的相互依赖。
- 实现依赖就绪任务的并发调度，落实默认 4 个任务的并发上限，以及 `parallelism`、`--jobs` 的覆盖规则。
- 完善 `ProcessBuilder` 的异常处理，落实停止新任务、等待运行中任务结束的失败策略，不会引起系统崩溃。
- 使用 TamboUI 实现 `review` 交互式静态计划审阅，展示依赖关系、拓扑顺序、命令和并发上限，支持键盘导航与纯文本降级，不执行任务。
- 实现基于文件的运行状态与日志持久化、异常退出识别及历史查询，不包含自动断点续跑。
- 完成 `init` 默认配置生成与 `status` 本地工具健康状态展示，形成首版完整命令集合。

## English

[Project home](../readme.MD)

This project aims to build a lightweight, efficient local DevOps orchestration toolbox with AI collaboration.

The technology stack, language conventions, build and packaging rules, resource management, and testing constraints are maintained in the [Copilot repository instructions](../.github/copilot-instructions.md). This document covers functional behavior, architecture, and the development roadmap.

### 1. Configuration format and loading

- Task configuration supports only UTF-8 YAML in `.devpulse.yaml`; `.devpulse.conf` is no longer read. See the [Core CLI guide](core-cli.md) for migration.
- Use SnakeYAML Engine's YAML 1.2 Core Schema to parse a single mapping document, limited to 1 MiB and 64 collection nesting levels. Reject duplicate keys, non-string keys, custom tags, anchors, aliases, and merge keys; never construct arbitrary Java objects.
- Convert YAML nodes to Typesafe Config values with file and line origins, reusing typed reads and duration validation without reparsing YAML strings as HOCON.
- Default to `version: 1`, `parallelism: 4`, and empty `pipelines`. Do not substitute configuration variables or load JVM system properties/environment variables; leave command variables to Bash.
- Do not support includes or remote resource loading. Keep complex logic in user-maintained local scripts.
- `tasks` is a mapping keyed by task name, with optional task `description`; `pipelines` select execution targets through task lists. Declaration order is not dependency order.
- Report configuration origins and line numbers when available. Validate field types, duplicate keys, unknown dependencies, and cycles before executing any command. Reject unknown root, task, readiness, and pipeline fields.
- Use YAML examples consistently in generated gather prompts and init configurations. Jira configuration and internal run records retain HOCON and are unaffected by task configuration migration.

### 2. Architecture

The system contains tool entry points, an environment inventory module, a reusable Bash component, a scheduling engine, a local daemon management module, a configuration initializer, and a plan-review module with separate responsibilities:

```text
CLI input -> standalone tool (or combined compatibility entry point) -> corresponding command implementation
```

#### 1. Tool entry points (`PulseCLI.java`, `GatherCLI.java`, `JiraCommand.java`)

**Responsibility**: Parse arguments and execute the commands owned by each tool. `DevPulseCLI.java` in `devpulse-cli` only preserves dispatch through the old combined entry point.

#### 2. Environment inventory (`GatherCommand.java`)

**Responsibility**: Collect local environment facts, generate an AI prompt in the selected language (Chinese/English by default), print it, and copy it to the clipboard by default.

**Implementation steps**:

- **Environment and tool inventory**: Record OS, architecture, Java version, and Bash location. Check a fixed tool list using Bash `command -v`; do not execute version commands or health checks.
- **Project detection**: Check only the presence of root-level markers such as `package.json`, `pom.xml`, `Dockerfile`, and `.git`. Do not read file contents or recursively scan directories.
- **Collection boundaries**: Use environment variables only for Bash discovery and tool lookup; do not include their values or credentials in the prompt. Prompts contain project and tool locations, which users must review before sharing with external AI services.
- **Prompt output**: Generate Markdown containing facts, a YAML example, Bash constraints, and command-review reminders. Support Chinese, English, or both. Bilingual mode retains same-page language navigation; single-language modes include only the corresponding language section without bilingual navigation. Keep local fact data such as paths and tool names, and the YAML example, unchanged. Require AI to state assumptions instead of inventing tasks when facts are insufficient.
- **Encoding warning**: Before printing the prompt, check Java's `Console.charset()`, or `System.out.charset()` when no console is available. If the detected encoding is not UTF-8, print an English warning to standard error with the detected encoding and Git Bash's `Options > Text > Character set > UTF-8` setting. Do not change the language, output encoding, clipboard content, or successful exit code. This check reflects Java's detected encoding and cannot reliably read the Git Bash window's actual display character set. It may also warn when output is redirected and does not establish that garbling has occurred.
- **Clipboard**: Copy through AWT by default. In headless environments or when the clipboard is busy or inaccessible, warn and retain standard output; inventory still succeeds.
- **Options**: `gather --directory <directory>` selects the project, `--bash <path>` selects Bash, and `--no-clipboard` disables clipboard writes. `--language zh|en|both` controls the prompt language for both terminal and clipboard output, defaults to `both`, and rejects unsupported values before inventory. Invalid directories, missing Bash, and scan failures return nonzero exit codes. Use `gather --language en` to avoid Chinese prompt text when the terminal displays it incorrectly; this option does not fix terminal encoding or translate local fact data.

#### 3. Bash component (`BashShell.java`)

- **Unified interpreter**: Use Bash on every OS and Git Bash on Windows. Report missing Bash explicitly; never fall back to `sh`, CMD, PowerShell, or WSL.
- **Discovery order**: An explicit `--bash` path takes priority, followed by PATH lookup. Also check Git installation locations on Windows and accept only Bash executables with Git installation markers to avoid WSL launchers.
- **Verification**: Verify that Bash actually starts using a short-lived subprocess, rather than checking file presence alone.
- **Invocation**: Use `ProcessBuilder` to run `bash --noprofile --norc -s --`. For batch tasks, write the complete UTF-8 script to standard input and close input; for daemon, readiness, and stop commands, redirect input from saved script files. Do not split commands on spaces or pass command text with `-c`, avoiding Windows command-line quoting altering quotes and variable expressions. This mode does not provide interactive input.
- **Environment and directory**: Set the working directory through `ProcessBuilder.directory()`. Inherit the parent environment, but remove `BASH_ENV`, `ENV`, and `BASH_FUNC_*` to avoid environment-selected startup files or imported user functions. Do not rely on aliases or user startup scripts.
- **Probe resource control**: Apply timeouts to verification and inventory, continuously read inventory output, and clean up probe processes.
- **Platform boundaries**: Shared Bash syntax does not make tools, paths, permissions, or OS capabilities identical. Git Bash is not a complete Linux environment.

#### 4. Scheduling engine (`WorkflowRun.java`, `PulseCommand.java`)

**Responsibility**: Read `.devpulse.yaml`, determine dependency order, and execute local subprocesses.

**Independent planning module**: The immutable `TaskDefinition`, `WorkflowDefinition`, and `TaskPlan` in `devpulse-core` depend only on the JDK rather than YAML, Typesafe Config, or the CLI. Task definitions contain commands, dependencies, and optional daemon settings; workflow definitions contain task lists and pipeline targets. `TaskPlan.create` validates the complete definition, expands transitive dependencies for a selected task or pipeline, and returns a topologically ordered plan without reading configuration, discovering Bash, executing commands, or writing run records.

```java
import io.github.devpulse.core.execution.TaskDefinition;
import io.github.devpulse.core.execution.TaskPlan;
import io.github.devpulse.core.execution.WorkflowDefinition;
import java.util.List;
import java.util.Map;

var workflow = new WorkflowDefinition(
        List.of(
                new TaskDefinition("check", "echo check", List.of()),
                new TaskDefinition("build", "echo build", List.of("check"))),
        Map.of("dev", List.of("build")));
var plan = TaskPlan.create(workflow, "dev");
```

`PlanningException` exposes format-independent error locations through `scope()`, `name()`, and `field()`. Pulse's `PulseConfiguration.plan` associates these with the original YAML file and available line numbers. Pulse's `TaskDetails` formats daemon terminal descriptions rather than placing them in core definitions.

**Independent execution runtime**: Core's `WorkflowRun` binds a plan and concurrency limit. Its blocking `execute()` may be called only once and returns the overall state and succeeded, failed, and skipped counts. `cancel()` may be called from another thread: cancellation before execution starts no tasks, repeated cancellation and cancellation after completion do not change terminal results, and external cancellation during execution interrupts the execution thread. Callers manage `RunHistory.Writer` with try-with-resources and decide whether to register a JVM shutdown hook; core registers no global hooks. Pulse maps success to exit code `0`, other results to `1`, and interruption to `130`.

`RunHistory` and `DaemonManager` also reside in core, preserving scheduling, failure policy, and version `1` HOCON record formats. The execution runtime uses Typesafe Config for these records but does not read task YAML or depend on picocli or the TUI. Embedded usage (continuing with the `plan` above):

```java
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.execution.RunHistory;
import io.github.devpulse.core.execution.WorkflowRun;
import java.io.PrintWriter;
import java.nio.file.Path;

var directory = Path.of(".").toAbsolutePath().normalize();
var shell = BashShell.discover(null);
try (var history = new RunHistory(directory).begin("dev", plan, 4)) {
    var run = new WorkflowRun(shell, directory, new PrintWriter(System.out),
            history, plan, 4);
    var result = run.execute();
}
```

**Implementation steps**:

- **Configuration**: Load YAML according to the configuration rules and construct configuration models.
- **Target selection**: `pulse` defaults to `dev`; `pulse <name>` accepts either a pipeline or a single task. Reject targets that name both a task and a pipeline and require the collision to be resolved.
- **DAG construction**: Treat each task in the `tasks` mapping as a vertex and each `needs` relationship as a directed edge.
- **Dependency expansion**: Pipeline `tasks` declare final targets. Include all transitive dependencies automatically and run shared dependencies or duplicate targets once per invocation. List order does not define dependency order.
- **Ordering and cycle detection**: Validate the dependency graph using Kahn's algorithm or DFS. Report cycles before starting commands. Only enqueue tasks whose dependencies are all satisfied: successful batch exit or successful daemon readiness. Schedule within the concurrency limit.
- **Process execution**: Reuse the Bash component with the project configuration directory as the working directory. Merge standard error and output using `redirectErrorStream(true)`. Continuously read UTF-8 batch output with task-name prefixes; redirect daemon output directly to log files. Tools must be installed and discoverable by Bash. Commands run with the current user's permissions; no sandbox is provided.
- **Concurrency**: Allow at most 4 concurrent batch tasks or daemon startup waits by default. Support `parallelism` and `pulse --jobs`, with CLI options overriding project configuration and defaults. Validate positive integers before execution. This does not limit ready daemons, probe processes, or concurrency inside external tasks.
- **Failure policy**: Use the Bash exit code: zero means success, nonzero means failure. When a long script exits successfully before consuming all input, tolerate input-write I/O errors caused by Bash closing stdin rather than misclassifying the task as failed; output reading and persistence failures must still be reported. Do not implicitly enable `errexit` or `pipefail`; commands may set them explicitly. Once failure is observed, stop launching new tasks, wait for started tasks to finish, return a nonzero exit code, and summarize succeeded, failed, and skipped counts.
- **Options**: `--directory` selects the project, `--bash` selects Bash, and `--jobs` overrides concurrency. `--dry-run` validates the whole configuration and prints the expanded plan and commands without discovering Bash or starting tasks.
- **Execution boundaries**: Support non-interactive batch tasks and explicitly configured local daemons, not ordinary batch timeouts, automatic restart, or a persistent scheduling platform. Cancellation attempts to terminate batches and visible descendants; daemon cleanup follows `shutdownHook`. Detached descendants are not guaranteed to be reclaimed.

#### 5. Configuration initializer (`InitCommand.java`)

**Responsibility**: Generate an editable `.devpulse.yaml` skeleton in the project directory. Do not scan the environment, call AI, execute tasks, or require an installed Bash.

- **Defaults**: UTF-8 YAML containing `version: 1`, `parallelism: 4`, two example tasks using only Bash `echo`, and a `dev` pipeline with a dependency relationship.
- **Directory selection**: Use the current working directory by default. `init --directory <directory>` selects another existing project directory; do not create directories automatically.
- **Overwrite protection**: Create new files only by default. Refuse existing configurations with a nonzero exit code. Allow overwriting regular files only with explicit `--force`; do not follow configuration-file symbolic links.
- **Feedback**: Print the configuration path and a command-review reminder on success. Clearly report invalid directories or write failures without executing example tasks.

#### 6. Local task execution persistence (`RunHistory.java`)

**Approach**: Persist files within the project without introducing a database. `pulse` saves the plan before launching tasks and continuously persists task states and logs. Multiple independent runs may execute concurrently.

```text
.devpulse\
  runs\
    <run-id>\
      state.conf
      run.lock
      logs\
        <task-id>.log
      daemons\
        <task-id>.conf
        <task-id>-start.sh
        <task-id>-ready.sh
        <task-id>-stop.sh
        <task-id>.lock
```

- **Storage layout**: Generate a unique `run-id` for each actual execution and create a separate run directory under `.devpulse\runs` in the project configuration directory, preventing repeated or concurrent runs from overwriting each other. Use generated `task-id` values for log filenames rather than using task names as paths. `--dry-run` creates no run records.
- **State format**: Use UTF-8 HOCON for `state.conf`, reusing Typesafe Config for reading and writing. Record the format version, run ID, target, project directory, effective concurrency limit, overall state and start/end times, plus each task's ID, name, dependencies, state, start/end times, exit code, failure reason, and relative log path. Omit fields such as end time and exit code until available.
- **State semantics**: Distinguish task states `PENDING`, `RUNNING`, `READY`, `SUCCESS`, `FAILED`, `SKIPPED`, `CANCELLED`, and `INTERRUPTED`. `READY` records daemon readiness during that execution, not its eventual exit. Ordinary failures are `FAILED`; unstarted tasks are `SKIPPED`; explicitly cancelled batches are `CANCELLED`; unconfirmed results after executor exit are `INTERRUPTED`. Overall pipeline outcome is separate from subsequent live daemon state.
- **Write timing and consistency**: Save the initial plan before execution, persist `RUNNING` before starting a task, save its result after completion, and write the overall result when the run ends. Attempt to save cancellation state on explicit cancellation. Serialize state writes within each run, writing a temporary file in the same directory before atomically replacing `state.conf`. Report filesystems without atomic replacement support instead of falling back to direct overwrite. Do not start tasks if initialization fails. Persistence failures during execution must be reported, stop new task launches, wait for started tasks to finish, and return a nonzero exit code.
- **Log persistence**: Batch output is continuously read, printed, and saved as UTF-8. Daemon output is merged and redirected directly to a file without depending on CLI reader threads or live console forwarding. Logs are independent of snapshots rather than rewriting `state.conf` per line. Unexpected exits may lose buffered tails; daemon programs must select suitable output encoding and flushing.
- **Unexpected exit detection**: Record the executor PID and process start time when available, and hold an exclusive file lock in the run directory during execution; PID alone cannot establish process identity. When a later state read finds an unfinished run, check the file lock and executor identity before marking it interrupted, without modifying another live run. Once the original executor is confirmed to have exited, mark remaining `RUNNING` tasks as `INTERRUPTED`, unstarted tasks as `SKIPPED`, and persist the interrupted run state. If identity cannot be confirmed, retain the record and report uncertainty.
- **Concurrent reads**: Read the complete state file and close it before parsing HOCON. Windows read handles can briefly block replacement; allow up to 200 ms of atomic replacement retries. Never fall back to non-atomic writes, and report persistent failures explicitly.
- **Queries**: `status` defaults to the most recently started run and shows overall state, task states, exit codes, failure reasons, and start times, end times, and durations for both the run and every task. `--run <run-id>` selects a historical run, `--list` lists all runs with their start/end times and durations, and `--log <task-name>` reads a task log from the selected run. Sort by start time so an older run finishing later does not overwrite a newer record. Successful queries return `0`, even when the recorded execution failed.
- **Timing display**: Use UTC ISO 8601 timestamps displayed to seconds, preserving the original precision in persisted timestamps. Select duration units automatically: integer `ms` below one second, `s` below one minute, `m` below one hour, and `h` otherwise. Seconds, minutes, and hours use at most two decimal places with trailing zeros removed, for example `303ms`, `2.76s`, `1.5m`, and `27h`. For running work, calculate elapsed time at query time and append `(running)`. Show `-` for all timing fields of unstarted tasks. Cancellation or interruption end times may represent cancellation or recovery rather than actual process exit; prefix their estimated durations with `~`. Show `-` when missing timestamps or backward system-clock changes prevent calculation. Derive durations from existing timestamps without persisting additional fields.
- **Environment checks**: `status --tools` retains Bash and tool availability checks with optional `--bash`. Do not run version commands or probe daemons such as Docker. All modes support `--directory`; do not combine tool mode with run-query options.
- **Query and recovery boundaries**: Reading history, lists, and logs needs no Bash or project configuration. Live daemon readiness and custom stopping use recorded Bash and scripts; missing Bash prevents confirming readiness. Older unrecorded runs cannot be backfilled. State files cannot include resources or resolve additional environment data. Do not resume, take over, or restart orphaned processes; manual cleanup targets only recorded, identity-confirmed instances. Future resumption must validate definitions and establish idempotency/retry policies.
- **Local data boundaries**: Run records and logs are generated data; exclude `.devpulse\` from version control. Do not persist environment-variable or credential snapshots. Task output may contain sensitive information and must be reviewed before sharing. Do not automatically prune history initially; consider SQLite later if extensive historical queries or cross-process transactions become necessary.

#### 7. Execution plan review (`ReviewCommand.java`, `ReviewTui.java`, `DagWidget.java`)

**Responsibility**: Statically validate a task or pipeline execution plan and provide an interactive TUI for user review before execution, without calling AI.

- **Targets and validation**: `review` defaults to `dev`; `review <name>` accepts a pipeline or a single task. Reuse `PulseConfiguration.load` and `PulseConfiguration.plan`, which calls core's `TaskPlan.create`, for the same full configuration validation, target collision checks, cycle detection, and transitive dependency expansion as `pulse`.
- **TUI and dependencies**: Use TamboUI `0.6.0` (the latest Maven Central release when integrated), with `tamboui-tui` and `tamboui-jline3-backend`. Define the version centrally in `pom.xml` and do not use snapshots. The left pane defaults to a layered DAG and can switch to a task list in topological order; the right pane shows the selected task's direct prerequisites, downstream tasks within the plan, and complete Bash command. DAG node numbers match task-list order; full names remain available in details when graph labels are truncated.
- **DAG layout and highlighting**: A custom TamboUI Widget lays out nodes left to right by longest dependency-path depth `L`, with nodes in each layer ordered top to bottom by topological order. Draw shared tasks only once and retain an arrow to the dependent node for every direct dependency. Route long cross-layer edges between node rows to avoid intermediate nodes. Crossing lines may share display cells without implying new dependencies; right-pane details provide the exact direct relationships. Highlight the selected node in yellow, all transitive prerequisites and their paths in green, and all transitive dependents and their paths in magenta; unrelated nodes and edges remain gray. Do not automatically eliminate all crossings.
- **Plan information and interaction**: Show the target, project directory, task count, effective concurrency limit, and color legend at the top. Up/Down selects tasks; `Home`/`End` selects the first/last task. Left/Right pans the DAG horizontally; `w`/`s` pans vertically; `r` centers the view on the selection. `Tab` switches between DAG and task list; `PageUp`/`PageDown` scrolls right-pane details; `q` quits. Center the graph and reset detail scrolling when selection changes. Clip graph output to its viewport without overwriting adjacent panes, wrap long commands by terminal display width, and display control characters as visible text rather than terminal instructions. Dependency depth and topological order are not fixed execution batches or a sequential schedule; actual scheduling still depends on successful prerequisites and the concurrency limit.
- **Terminal lifecycle and fallback**: Use JLine to detect the standard-output terminal. When no interactive terminal is available, such as with redirection or pipes, automatically retain plain-text output without reading interactive keys. `--plain` explicitly selects the original ASCII dependency list and command output. Interactive mode uses an alternate screen and raw input, with try-with-resources and TamboUI cleanup restoring the screen, cursor, and input state. Reflow on terminal resize; below `60 x 16`, prompt users to enlarge the window while still allowing quit.
- **Options**: `--directory` selects the project configuration directory. `--jobs` only overrides the displayed concurrency limit without modifying configuration, using the same positive-integer constraints and precedence as `pulse`. `--plain` disables the TUI. Do not provide `--bash`, since review does not require Bash.
- **Execution boundaries and feedback**: Do not probe tools, start Bash, execute tasks, or write run records. Return `0` for valid plans and normal quit; report invalid configuration, targets, or TUI initialization/rendering with a nonzero exit code. Successful static validation does not guarantee command safety or tool availability. Retain existing `pulse --dry-run` behavior.

#### 8. Built-in CLI integration: Jira (`JiraCommand.java`)

**Module and interface**: The root `pom.xml` aggregates the modules. `devpulse-dependencies` manages external dependencies, `devpulse-bom` manages consumable internal artifact versions, and the root POM's `pluginManagement` manages build plugin versions. `devpulse-core` provides independent workflow definitions, planning, execution runtime, run history, and daemon management alongside shared Bash and environment inventory code, without an executable entry point. `devpulse-plugins/pulse` contains the workflow commands `pulse`, `init`, `review`, `status`, and `stop`, YAML-to-core definition conversion, terminal presentation, and CLI lifecycle adaptation; `devpulse-plugins/gather` contains prompt generation; `devpulse-plugins/jira` accesses Jira independently. Each tool JAR depends only on modules it needs, with no tool-to-tool dependency. The shared library does not register commands or load plugins dynamically. `devpulse-cli` preserves the combined `devpulse <subcommand>` entry point but is excluded from individual tool archives. `devpulse-dist` depends on tool artifacts and assembles selective archives; tools never depend on the distribution module. Runtime modules inherit `devpulse-dependencies` via local relative parent paths, and the internal BOM is not a runtime dependency. See [distribution and installation](distribution.md).

**Version source**: The root `pom.xml`'s `<revision>` is the only project version value. Maven 3 still requires each child's `<parent><version>` element, so it references `${revision}` rather than repeating a literal version; internal module dependencies and the BOM use `${project.version}`. The inherited Flatten Maven Plugin resolves CI-friendly version placeholders so installed or deployed POMs remain consumable by other Maven projects. External dependency versions, build plugin versions, and the HOCON configuration schema version have separate meanings and do not advance with the project version.

**Jira implementation**: Java's HTTP client accesses Cloud REST v3 or Data Center REST v2; site, deployment type, and credentials come only from environment variables. Remote plaintext HTTP is rejected and requests with authorization headers are not redirected. `init` creates a HOCON field template and adjacent Markdown description; `create`, `issue view`, `export`, `update`, `transitions`, `transition`, `comment`, `comment-export`, `comment-update`, and `attach` provide basic issue management. Export uses `/editmeta` to limit fields, preserving their original structure with a field baseline and description hash; issue and comment updates send only changes and check `updated` before uploading, but do not promise atomic conflict control. Cloud supports a strict subset of ADF/Markdown conversion; Data Center descriptions and comments are currently plain text only, and unsafe rich-text round-trips are refused. See [Jira CLI](plugins/jira.md) for options, safety boundaries, and examples.

#### 9. Local daemon management (`DaemonManager.java`, `StopCommand.java`)

- **Configuration and startup**: Tasks may declare `type: daemon`, required `ready.cmd`, check intervals, and timeouts. `shutdownHook` defaults to false, with optional `stop` and `stopTimeout`. Save launch, readiness, and stop scripts by task ID; atomically persist PID, process start time, Bash path, and stop settings in separate HOCON files. If identity or metadata cannot be recorded, attempt to terminate the untracked instance.
- **Dependencies and concurrency**: Batch prerequisites require successful exit; daemon prerequisites require readiness. Readiness releases an execution slot while retaining the process and monitoring it. `--jobs` does not limit ready daemons or probe processes. Each daemon uses a virtual thread for periodic, bounded checks. Unexpected exit or readiness loss fails the run and stops new tasks without automatic restart.
- **Lifecycle**: On completion or handled exit, clean up only hook-enabled instances in reverse plan dependency order. Other instances survive CLI exit, when monitoring ends. Later detailed `status` queries check liveness and readiness on demand; there is no separate background scheduler. Readiness is not a cross-process recovery checkpoint.
- **Cleanup and ownership**: `stop [TASK] --run <ID>` uses saved settings, defaults to the latest run, and allows omitting the task to clean all instances. Per-instance cleanup uses a separate lock independent of run-state writes. PID/start-time mismatches never terminate a reused PID; uncertain identity is refused. Stop commands receive instance identity environment variables. Execute bounded stopping, then attempt forced cleanup of confirmed processes and visible descendants. Do not kill by process name or delete business data.
- **Review and diagnostics**: Static review displays all lifecycle commands and timeouts without probing. `status` separates historical startup outcomes from live `STARTING` / `READY` / `NOT_READY` / `EXITED` / `STOPPED` / `UNKNOWN` states. Lists and log queries do not execute checks. Logs, metadata, and scripts are local generated data that may contain sensitive content; do not commit or share them without review.

### 3. Development roadmap

The first release includes `init`, concurrent `pulse`, `gather`, `review`, and `status`. Concurrency and AI collaboration are not deferred to later releases. These stages describe internal implementation order, not separate release scopes.

#### Stage 1: Project scaffold and CLI skeleton (MVP)

- Set up the build and CLI skeleton according to the Copilot repository instructions.
- Ensure the executable JAR prints standard help with `--help`.

#### Stage 2: Gather and AI collaboration

- Implement reusable Bash discovery and invocation.
- Implement tool lookup, project marker detection, and bilingual prompt generation.
- Implement AWT clipboard writes with headless fallback and a print-only option.

#### Stage 3: Pulse and scheduling

- Parse YAML task configuration with SnakeYAML Engine, preserve source lines, and reuse Typesafe Config to merge defaults and validate before execution.
- Implement Kahn-based dependency ordering for complex graphs.
- Implement dependency-ready concurrent scheduling with the default limit of 4 and `parallelism`/`--jobs` overrides.
- Handle `ProcessBuilder` failures by stopping new tasks and waiting for started tasks without crashing the application.
- Implement interactive static `review` using TamboUI, with dependencies, topological order, commands, concurrency limits, keyboard navigation, and plain-text fallback without executing tasks.
- Implement file-based run-state and log persistence, unexpected exit detection, and historical queries without automatic resume.
- Complete init configuration generation and status health reporting for the first release's full command set.
