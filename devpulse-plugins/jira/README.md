# Jira 插件指南 / Jira plugin guide

[中文](#中文) | [English](#english)

## 中文

[项目首页](../../readme.MD) | [架构设计](../../docs/devpulse-plugins/jira/architecture.md)

`devpulse-jira` 是 `devpulse-plugins` Maven 聚合模块下独立可运行的 `jira` 子模块与 Jira REST 客户端，支持 Jira Cloud（默认 REST v3）和 Data Center（默认 REST v2），可显式配置 API 版本。核心 `devpulse` 不加载或依赖它，也不需要 Bash 来执行 Jira JAR。根目录 `mvn clean package` 生成 `devpulse-plugins/jira/target/devpulse-jira.jar`；`bash run-jira.sh` 仅构建该模块并启动。**未连接真实 Jira 实例验证**；先在测试项目中检查字段格式、权限与工作流。

### 连接配置与认证

连接配置使用 UTF-8 HOCON，与 `issue create --file` 等命令的 issue 模板完全独立。读取优先级为：显式 `-c/--config` 指定的文件、**当前工作目录**的 `local_jira_config.conf`、用户 home 下 `.jira` 目录的 `local_jira_config.conf`。选择整份文件，不合并本地和全局配置；显式文件缺失或本地文件无效时直接报错，不跳过它改用全局配置。

```hocon
url = "https://example.atlassian.net"
mode = "cloud"                  # Data Center 使用 "dc"
email = "user@example.com"       # Cloud 必填，Data Center 可省略
project = "DEMO"                # create/list 的默认 project key
# apiVersion = 3                # 可选：2 或 3；省略时 Cloud=3、Data Center=2
```

仅认证令牌从环境变量读取，不在连接配置或 issue 模板中保存：

```bash
export JIRA_API_TOKEN='your-api-token'          # Cloud API Token / Data Center PAT
```

使用全局 `-c/--config` 指定其他连接配置文件；该选项可以放在子命令前或后，相对路径仍以当前工作目录为基准：

```bash
bash run-jira.sh --config connection.conf issue list -p DEMO
bash run-jira.sh issue list -p DEMO --config connection.conf
```

`url` 和 `mode` 必填；`mode` 只接受 `cloud` 或 `dc`，Cloud 还必须提供 `email`。Cloud 使用邮箱与 token 的 Basic 认证，Data Center 使用 PAT 的 Bearer 认证。仅 `JIRA_API_TOKEN` 从环境变量读取，旧的 `DEVPULSE_JIRA_*` 环境变量不再使用。连接配置仅接受 `url`、`mode`、`email`、`apiVersion`、`project`，不支持 `token` 字段、HOCON `include`、环境变量或 JVM 系统属性替换。帮助、版本、连接初始化和离线 issue 模板生成不需要已有连接配置或 token。旧 `.jira.conf` 不再自动读取，可改名为 `local_jira_config.conf`，或通过 `--config` 显式指定。

`project` 是默认项目 key，初始化向导要求设置；手写的旧配置可省略它以保留原行为。`issue create` 的项目优先级是显式 `-p/--project`、issue 模板中的 `fields.project`、连接配置中的 `project`；`issue list` 优先使用显式 `-p`，否则使用默认项目。完整 `--jql` 不会自动附加默认项目条件。

`apiVersion` 为可选的数值 `2` 或 `3`；未配置时保留原规则：`mode = "cloud"` 使用 3，`mode = "dc"` 使用 2。显式值覆盖默认选择，请求路径使用 `/rest/api/{apiVersion}/`，描述与评论按 API v2 文本或 API v3 ADF 格式处理；认证、用户标识和搜索分页方式仍由 `mode` 决定。指定版本并不保证 Jira 部署提供相应接口，例如 Data Center 通常不提供 v3，需确认服务器实际支持。

使用 HTTPS；仅本机测试允许 HTTP。请勿将包含凭据的 shell 历史、环境转储、导出的 Jira 数据或附件提交到版本库。访问失败只显示 HTTP 状态码，不输出 Jira 的错误正文。

### 由 CLI 提供的需求工作流 Skill

JAR 内置 `jira-requirements` Skill、执行说明、规格书模板及 Jira 描述模板。CLI 只负责离线导出和 Jira 操作；由加载 Skill 的 AI 分析需求、撰写规格并填充模板，不在 CLI 内调用 LLM。

```powershell
# 导出到新的项目级 Skill 目录；不需要连接配置或 token
jira skill export --output .github\skills\jira-requirements

# 默认导出英文；需要中文时显式选择
jira skill export --output chinese-skill --language zh

# 显式选择项目模板目录，也支持 --machine 返回导出清单与模板来源
jira --machine skill export --output exported-skill --templates team-templates
```

导出包含 `SKILL.md`、`references\workflow.md`、`templates\specification.md` 和 `templates\jira-description.md`，可放入所用 AI 工具支持的 Skill 目录。内置 `SKILL`、`workflow` 和 `jira-description` 各按 `.en.md` 和 `.zh.md` 分开维护，导出时保留上述标准文件名。`--language en|zh` 选择说明和默认模板正文的语言，默认为英文，不导出中英对照版本；Skill 元数据标识与触发描述保持稳定。不会自动安装到个人 home、加载或执行 Skill。目标目录必须不存在（已有空目录也拒绝），不会覆盖手工定制；升级时先导出到新目录再比较。

默认从**当前工作目录**的 `.jira\templates` 读取同名 `specification.md`、`jira-description.md` 覆盖内置模板；与连接配置位置无关。`--templates DIR` 改为只从该目录选取覆盖，不混用默认项目目录。两个文件可只提供一个，缺失文件回退所选语言的内置版本；已有但无效、空白、非 UTF-8 或超过 1 MiB 的模板直接报错，其他文件忽略。自定义模板保持原样，不自动翻译、不解析占位符、不执行模板指令。AI 按项目语言规范填充模板正文，不把使用说明或代码围栏上传到 Jira。

Skill 定义两条业务流程：新需求经澄清形成规格书，按模板生成 Jira 描述；用户确认目标及具体创建内容后直接创建，记录 key 并核验，不额外要求终端审批。已有 Jira 则先读取、严格导出并保留基线，AI 评审和生成规格/描述差异，**用户确认规格及具体差异后直接同步**，使用导出文件的更新时间检查，不使用 `--force`。规格、目标或内容改变后需重新确认。

这是 Skill 的协作规范，不是 CLI 新增的授权机制；工具权限审批及 Jira 权限仍有效，CLI 不额外设置终端审批。导出格式不是服务器身份绑定，Skill 要求保持连接、用户及 Issue 身份一致；不能无损导出、发生冲突或写入结果未知时停止核验，不自动覆盖或重复创建。`issue template` 仍是原有 HOCON/Markdown Issue 文件生成器，与此 Skill 的业务描述模板不同。

### AI 与脚本调用：`--machine`

全局 `--machine` 可放在子命令前或后，启用版本化 JSON 协议；未指定时保留原有的人类交互和 `--json` 输出方式。

```bash
jira --machine issue list -p DEMO --limit 20
jira issue view DEMO-1 --machine
# 写操作示例：仅在调用方已取得用户批准后执行
jira --machine issue create -p DEMO -t Task -s "Approved task"
jira --machine issue edit DEMO-1 -s "Approved summary"
jira --machine issue comment add DEMO-1 "Approved comment"
```

每次调用向 stdout 输出一个 JSON 对象，包含 `schemaVersion: 1` 和 `ok`。成功时包含 `data` 和 `messages`：原本支持 `--json` 的查询自动选择原始 JSON，保留分页信息；创建返回 Jira 的创建结果（含 `key`），编辑及评论返回 `{key, changed}`，`me` 返回账户标识对象，`open` 返回 `{url}` 且不启动浏览器。其他命令暂以 `data: null` 和人类文本 `messages` 返回；帮助及版本也是文本消息，**不要将 messages 当作稳定的机器字段解析**。

```json
{"schemaVersion":1,"ok":true,"data":{"key":"DEMO-1","changed":true},"messages":[]}
```

机器模式强制禁止交互提示，并禁用隐式 stdin 读取。创建描述、评论正文缺失时不会等待打开的输入管道；需要 stdin 时显式使用 `--template -`（创建/编辑还需按输入选择 `--template-format markdown|wiki`）。缺少必填值直接失败。查询仍只取一页，不自动追踪分页游标；不与 `--plain`、`--csv` 等冲突的输出选项混用。CLI 的人类诊断不输出到 stderr，也不混入 JSON；为防止输入或服务端内容泄漏，本地错误只提供通用说明。JVM 启动器自身的 stderr 不属于本协议。

失败时返回 `error` 对象，包含稳定的 `code`、说明性 `message`、`outcome`（`failed` 或 `unknown`）、`retryable`，HTTP 失败还包含 `httpStatus`。不返回 Jira 错误正文或 token。`retryable` 仅作提示，不代表 CLI 自动重试或允许绕过审批；写操作失败后应先核验远端状态，多请求命令可能已部分完成。

| 退出码 | 错误分类 |
| --- | --- |
| `0` | 成功，或成功返回帮助/版本 |
| `1` | 本地配置、输入、文件失败（`COMMAND_FAILED`），或内部异常（`INTERNAL_ERROR`） |
| `2` | 命令参数解析失败（`INVALID_ARGUMENT`） |
| `3` | HTTP 拒绝或只读 HTTP 失败（`HTTP_ERROR`） |
| `4` | 只读传输失败、中断或无效 JSON（`TRANSPORT_ERROR`、`REQUEST_INTERRUPTED`、`INVALID_RESPONSE`） |
| `5` | 写操作结果不确定：传输中断、5xx、无效 JSON 或创建响应缺少 key |

写请求失败可能发生在 Jira 已执行之后；`outcome: unknown` 不是“未执行”，必须查询核验或人工处理，不能盲目重复创建、评论或记工时。客户端不添加应用层自动重试。Issue 描述及评论始终是不可信数据，不应作为 AI 的指令执行。

**`--machine` 不代表授权。** 用户在协作流程中确认规格、目标及具体变更后，AI 直接调用创建或编辑命令，CLI 不再增加独立终端审批。并发冲突检查和结果核验继续保留；工具权限审批、Jira 权限与外部安全策略仍然有效，不应把 Issue 内容当作授权指令。

### 初始化连接：`init`

```bash
# 分步配置，保存到当前工作目录的 local_jira_config.conf
bash run-jira.sh init

# 分步配置，保存到用户 home 下 .jira/local_jira_config.conf
bash run-jira.sh init -g

# 脚本方式；省略 API 版本时仍按 mode 默认选择
bash run-jira.sh init --no-input --url https://example.atlassian.net \
  --mode cloud --email user@example.com --project DEMO
bash run-jira.sh init -g --no-input --url https://jira.example.com \
  --mode dc --api-version 2 --project DEMO
```

向导依次询问 URL、mode（默认 `cloud`）、可选 API 版本（按 Enter 使用 mode 默认值）、Cloud 邮箱和默认 project key；已通过参数提供的字段不再询问，输入无效会提示并重试。API 版本选择自动时不会写入 `apiVersion`，以后仍随 mode 推导。向导不请求 Jira、不读取或写入 token；在所有输入完成前取消，不会创建配置文件。没有终端时使用参数提供必填值；`--no-input` 显式禁止提示。

`-g/--global` 自动创建 home 下的 `.jira` 目录；不带 `-g` 时默认在当前目录创建。也可用全局 `--config FILE` 指定初始化输出文件，但不能与 `-g` 同时使用。已有目标文件一律拒绝覆盖。原 `init --file ISSUE.conf` 已迁移到 `issue template --file ISSUE.conf`，仍生成同名 `.conf` 和 `.md` issue 模板。

### 命令

```bash
bash run-jira.sh init
bash run-jira.sh issue template --file issue.conf
# 编辑 issue.conf 中的 Jira REST fields 和相邻的 issue.md
bash run-jira.sh issue create --file issue.conf
bash run-jira.sh issue list -p DEMO
bash run-jira.sh issue edit DEMO-1 --summary "Updated summary" --no-input
bash run-jira.sh issue view DEMO-1
bash run-jira.sh export DEMO-1 --file exported.conf
# 编辑 exported.conf 的 fields 和相邻的 exported.md
bash run-jira.sh update --file exported.conf
bash run-jira.sh transitions DEMO-1
bash run-jira.sh transition DEMO-1 11
bash run-jira.sh comment DEMO-1 --file comment.md
bash run-jira.sh comment-export DEMO-1 123 --file existing-comment.md
# 编辑 existing-comment.md 后上传
bash run-jira.sh comment-update DEMO-1 123 --file existing-comment.md
bash run-jira.sh attach DEMO-1 --file diagram.png
```

`init`、`issue template` 和 `export` 只创建新文件，不覆盖已有文件。创建时需要项目、类型和标题，项目可由连接配置提供；使用文件模式时描述取同名 `.md`。导出仅保留 Jira `/editmeta` 返回的可编辑字段及描述，其他字段不会被回写；自定义字段保持原始 JSON/HOCON 结构，需按该 Jira 实例的 REST 字段格式修改。`update` 比较导出时的字段，只发送修改过的字段；修改描述时将 Markdown 转成 Jira 内容格式。更新成功后重新导出再编辑。

`update` 和 `comment-update` 上传前比较导出时和当前 Jira 的 `updated` 值，发现变化默认拒绝；`--force` 显式跳过检查。评论导出创建 `.md` 与同名 `.conf` 元数据，成功更新后需要重新导出。**这是上传前检查，不是原子并发锁**：检查与更新之间若发生并发写入仍可能冲突。顶层 `transition` 使用 `transitions` 查到的**转换 ID**；`issue move` 还支持转换名称或目标状态名称。新评论、状态转换和附件不使用导出文件的并发检查。

问题及评论导出会记录实际 `apiVersion`；更新时必须与当前配置一致，切换版本后需要重新导出，`--force` 不跳过此检查。没有该字段的旧导出按原规则解释为 Cloud v3 或 Data Center v2。

### 创建问题：`issue create`

借鉴 [JiraCLI](https://github.com/ankitpokhrel/jira-cli) 的创建体验，支持交互、直接参数与原有文件模式。创建入口统一为 `issue create`，顶层 `create` 已移除。`issue` 分组还提供 `edit`、`assign`、`move`（别名 `transition`）、`list`、`view`、`link`、`unlink`、`clone`、`delete`、`comment add`、`worklog add` 和 `template`；原有顶层操作保持兼容。参考工具的全屏 TUI、导航快捷键及分页器不在本实现范围内。

```bash
# 在终端交互输入项目 key、问题类型、标题与可选的单行描述
bash run-jira.sh issue create

# 非交互创建；类型、优先级等名称须匹配 Jira 实例的实际配置
bash run-jira.sh issue create --no-input \
  -p DEMO -t Bug -s "Fix login timeout" -y High \
  -l backend -l urgent -C Backend --fix-version v2.0 \
  --affects-version v1.0 -b "Login times out after 30 seconds."

# 从 UTF-8 文件或标准输入读取多行描述
bash run-jira.sh issue create --no-input \
  -p DEMO -t Task -s "Document deployment" --template description.md
printf 'Deployment steps.\n' | bash run-jira.sh issue create --no-input \
  -p DEMO -t Task -s "Document deployment" --template -

# 可选 Epic；省略 --epic 或传入空字符串均可跳过
bash run-jira.sh issue create --no-input \
  -p DEMO -t Story -s "Add login flow" --epic DEMO-100

# 父任务、自指派与自定义字段（按实例要求使用原始 REST 字段 ID 和值）
bash run-jira.sh issue create --no-input \
  -p DEMO -t Sub-task -s "Add timeout handling" -P DEMO-1 -a me \
  --custom 'customfield_10001=5' \
  --custom 'customfield_10002={value="Backend"}'

# 保留文件模式；显式参数覆盖文件中的对应字段
bash run-jira.sh issue create --file issue.conf -s "Updated summary"
```

必填项为项目（`-p/--project`）、类型（`-t/--type`）和标题（`-s/--summary`）；也可由 `--file` 的 `fields` 提供，项目和类型支持原始 REST `id` 对象。未显式提供项目且模板中没有该字段时使用连接配置的默认项目。终端中默认补问仍缺少的必填项；未指定描述来源时，再询问可选描述。`--no-input` 禁止所有提示；无终端时也不会等待交互，必填项缺失则在请求 Jira 前报错。创建会直接提交，不额外询问确认；脚本建议始终指定 `--no-input`。

`-y/--priority` 设置优先级名称；`-l/--label`、`-C/--component`、`--fix-version` 和 `--affects-version` 可重复，显式提供时**替换**模板中的整个对应列表，不按逗号拆分。`-P/--parent` 设置父问题 key，是否可用由 Jira 的项目类型和层级决定。`-a/--assignee` 在 Cloud 使用账户 `accountId`，在 Data Center 使用用户名；特殊值 `me` 查询当前用户，`x` 取消指派，`default` 使用项目默认经办人。不按显示名模糊查找用户。

`--epic KEY` 可选地指定所属 Epic；省略或传入空字符串不额外设置关联。终端创建时，在必填项之后询问 Epic key，按 Enter 跳过；`--no-input`、文件模式、已设置父任务或 `epic create` 不补问。Cloud 使用 `fields.parent.key`，不允许与模板中的 `parent` 同时设置；Data Center 根据字段元数据自动查找 Epic Link，显式 Epic 覆盖模板或 `--custom` 中对应的字段。`--epic` 不与 `--parent` 同时使用；Jira 最终校验目标是否为 Epic、层级和权限，关联与创建在同一次请求中提交。

描述优先级为 `-b/--body` 或 `--template`、`--file` 同名 `.md`、管道输入或交互输入；`--body` 与 `--template` 互斥。`--template -` 显式读取标准输入直至结束；没有其他描述来源且无终端时也会读取标准输入，支持 `printf 'Description' | bash run-jira.sh issue create -t Task -s Summary --no-input`。标准输入为空时不发送 `description` 字段；使用 `--file` 且未覆盖描述来源时仍要求相邻 `.md`。`fields.description` 不可用于传入描述。

创建和直接编辑时，`--template-format auto|markdown|wiki` 默认 `auto`：`--template` 文件的 `.md` / `.markdown` 后缀不区分大小写，识别为 Markdown 并转换；其他后缀、无后缀和 `--template -` 均按 Wiki 原样提交，不根据内容猜测。可显式指定 `--template-format markdown` 或 `wiki` 覆盖后缀判断；显式格式要求提供 `--template`，不能与 `--body-format` 同时指定。为兼容已有用法，`--template-format auto` 下显式的 `--body-format` 优先于后缀判断。Wiki 仅支持 API v2；API v3 的非 Markdown 后缀模板或标准输入需显式指定 Markdown。此规则仅影响 `issue create --template` 和 `issue edit --template`，其他描述来源和评论的默认格式不变；`issue edit --file` 仍按导出元数据选择格式。

`--custom customfield_ID=HOCON_VALUE` 可重复，支持字符串、数字、布尔值、`null`、对象和数组；字符串含空格或特殊字符时应按 HOCON 规则加双引号，并使用 shell 引号保护整个参数。只接受原始 `customfield_数字` 标识，不解析显示名；显式值覆盖文件中同名字段。更复杂的字段配置仍建议使用 `--file`。本地校验必填项和输入格式，Jira 最终校验创建权限、字段可用性及额外必填字段；当前不自动发现创建元数据，也不提供编辑器或交互式用户/字段选择。描述转换仍受下面的 Markdown 边界限制。

### 编辑问题：`issue edit`

```bash
# 无编辑参数时，在终端交互输入标题、优先级、经办人和单行描述
# 每项按 Enter 保留原值；中断或输入结束不会提交
bash run-jira.sh issue edit DEMO-1

# 仅修改指定字段；传入编辑参数后不再补问其他字段
bash run-jira.sh issue edit DEMO-1 --no-input \
  -s "Fix intermittent login timeout" -y High -a me

# 列表字段采用增删语义，不替换整个现有列表
bash run-jira.sh issue edit DEMO-1 --no-input \
  -l backend --label=-urgent -C Backend --component=-Frontend \
  --fix-version v2.0 --fix-version=-v1.0 \
  --affects-version v1.1 --affects-version=-v1.0

# 从 UTF-8 文件或标准输入替换描述
bash run-jira.sh issue edit DEMO-1 --no-input --template description.md
printf 'Updated description.\n' | bash run-jira.sh issue edit DEMO-1 \
  --no-input --template -

# 取消指派、自定义字段、类型与父任务
bash run-jira.sh issue edit DEMO-1 --no-input -a x \
  --custom 'customfield_10001=8' \
  --custom 'customfield_10002=null'
bash run-jira.sh issue edit DEMO-1 --no-input -t Sub-task -P DEMO-2

# 复用已有的导出/编辑/更新流程；KEY 必须与导出文件一致
bash run-jira.sh export DEMO-1 --file exported.conf
# 编辑 exported.conf 和相邻的 exported.md
bash run-jira.sh issue edit DEMO-1 --file exported.conf --no-input
```

`edit` 由独立的 `JiraIssueEditCommand` 实现，问题 key 或 ID 为必填位置参数；使用导出文件时必须与文件中的 `key` 完全一致。直接编辑支持标题（`-s`）、类型（`-t`）、优先级（`-y`）、经办人（`-a`）、父任务（`-P`）、描述以及原始 `customfield_数字` 自定义字段；自定义值的 HOCON 解析规则与创建相同。经办人使用 Cloud `accountId` 或 Data Center 用户名，支持 `me` 自指派和 `x` 取消指派，不支持 `default` 或按显示名查找。类型和父任务变更受 Jira 实例的编辑权限与层级规则限制；此命令不负责跨项目移动或状态流转。

标签（`-l/--label`）、组件（`-C/--component`）、修复版本（`--fix-version`）、影响版本（`--affects-version`）均可重复：普通值表示新增，前缀 `-` 表示移除，建议用 `--label=-old` 这类等号写法避免与选项混淆。不按逗号拆分；重复的同向操作去重，同一值同时新增和移除会在请求 Jira 前报错。增删通过 Jira `update` 操作提交，不先读取整个列表再覆盖。未指定的字段保持不变；若需清空或替换整个列表，使用导出文件模式明确编辑对应数组。

`-b/--body` 和 `--template` 互斥；`--template -` 显式读取标准输入直至结束。空描述文本或空描述文件会清空远端描述，未提供描述来源则不修改；交互模式中的空输入仅表示保留，不能用于清空。没有编辑参数时才交互询问标题、优先级、经办人与单行描述；按 Enter 保留，若全部保留则不提交。`--no-input` 或没有终端时，必须提供编辑参数或 `--file`。指定编辑参数会直接提交，不额外询问确认。

`issue edit --template` 与创建使用相同的 `--template-format` 自动识别和显式覆盖规则；空文件仍表示清空描述。`--template-format markdown|wiki` 不能单独使用，也不能与 `--file` 混用。

直接编辑先读取当前问题，仅发送显式变更，跳过可识别的未变化字段；写入前通过 `/editmeta` 检查字段可编辑性及所需的 `set`、`add`、`remove` 操作，并再次比较问题的 `updated` 值，发现并发变化则拒绝提交。`--force` 只跳过更新时间检查，不绕过 Jira 字段权限。**更新时间检查不是原子并发锁**；检查与实际写入之间仍可能出现竞争。描述转换仍受下面的 Markdown 边界限制。

`--file` 仅接受已有 `export` 生成的文件，复用顶层 `update` 的字段差异、模式及导出时更新时间检查；不与任何字段编辑参数混用。成功更新后需要重新导出。顶层 `update` 保持兼容，不新增顶层 `edit`。

### 指派用户：`issue assign`

```bash
# 交互输入问题 key 和用户；多人匹配时按编号选择
bash run-jira.sh issue assign
# 仅补问用户
bash run-jira.sh issue assign DEMO-1
# 参数齐全且用户唯一时直接提交
bash run-jira.sh issue assign DEMO-1 "Jon Doe"
# 取消指派或指派给自己
bash run-jira.sh issue assign DEMO-1 x
bash run-jira.sh issue assign DEMO-1 me
```

普通用户通过当前问题的 `/user/assignable/search` 查询，支持显示名和 Data Center 用户名；Cloud 使用 `query`，Data Center 使用 `username`，写入时分别使用 `accountId` 和 `name`。优先采用名称或标识完全匹配的结果，否则要求搜索结果唯一。多人匹配时，有终端且未指定 `--no-input` 则提供编号选择；无终端或禁止交互时直接报错，不偷偷选择第一人。单次查询最多 50 人，达到上限则要求缩小搜索范围，避免根据截断结果误指派。

`--no-input` 显式禁止交互，必须提供 KEY 和 USER；无终端且缺少参数时同样在请求 Jira 前报错。`me` 查询当前用户，`x` 通过空用户标识取消指派，均不搜索用户。命令使用专用 `PUT /issue/{key}/assignee`，不改动其他字段，也不使用导出文件的并发检查；Jira 最终校验指派权限及是否允许未指派。输入结束或选择前中断不会提交。

### 状态流转：`issue move`

```bash
# 交互输入问题 key，并从当前可用转换中按编号选择
bash run-jira.sh issue move
# 指定 key 时仅选择转换；参数齐全且唯一匹配时直接提交
bash run-jira.sh issue move DEMO-1
bash run-jira.sh issue move DEMO-1 "In Progress" --no-input
# 转换名称或转换 ID；issue transition 是同一命令的别名
bash run-jira.sh issue transition DEMO-1 11 --no-input
# 在同一次转换请求中添加评论、设置解决结果和经办人
bash run-jira.sh issue move DEMO-1 Done -R Fixed -a me \
  --comment "**Completed**" --no-input
bash run-jira.sh issue move DEMO-1 Done --comment "*Completed*" \
  --body-format wiki --no-input
```

此处的 move 表示工作流状态转换，**不是跨项目迁移**。命令先读取 `/issue/{key}/transitions?expand=transitions.fields`，按转换 ID、转换名称、目标状态名称的优先级精确匹配；名称不区分大小写，不做模糊匹配。找不到可用转换时拒绝提交；同一优先级有多个匹配时，终端交互允许按编号选择，`--no-input` 或无终端时必须改用唯一的转换 ID。菜单显示转换名称、ID 和目标状态，不会默认选择第一项。缺少参数且无法交互时，在请求 Jira 前报错；输入结束或选择前中断不会提交。

`--comment` 使用 Markdown；v2 转成 Wiki，v3 转成 ADF，转换边界与正文相同。原生 Wiki 评论需显式添加 `--body-format wiki`，仅适用于 v2。`-R/--resolution` 依据转换元数据中的允许值匹配解决结果名称或 ID；没有允许值列表时按名称交给 Jira 校验。`-a/--assignee` 接受 Cloud `accountId` 或 Data Center 用户名，支持 `me` 自指派和 `x` 取消指派，不支持 `default` 或按显示名搜索。

附带字段必须由所选转换的元数据公开，并支持对应的 `set` 或 `add` 操作，否则本地拒绝。解决结果、经办人和评论通过同一次 `POST /issue/{key}/transitions` 提交，不先单独修改字段或新增评论，避免转换失败后留下这些独立写入。其他工作流必填字段、权限及最终可用性由 Jira 校验；当前不自动补问额外必填字段，也不提供状态转换的导出时间并发检查。原有顶层 `transitions KEY` 和 `transition KEY ID` 保持兼容。

### 查询问题：`issue list`

```bash
# 默认查询配置的项目；无默认项目且无筛选时查询最近 30 天创建的问题
# 每次最多返回一页
bash run-jira.sh issue list
bash run-jira.sh issue list -p DEMO -s "In Progress" -a me
bash run-jira.sh issue list -p DEMO -t Bug -y High -l backend -l urgent
bash run-jira.sh issue list -p DEMO -s '~Done' -a x --created -7d
bash run-jira.sh issue list -p DEMO -r me --updated -1d \
  --order-by updated --reverse --limit 20

# 完整 JQL（包括排序）；不能与筛选和排序参数混用
bash run-jira.sh issue list --jql \
  'project = DEMO AND (status = Open OR priority = High) ORDER BY updated DESC'

# JSON 保留 Jira 的分页信息；纯文本模式输出 TSV，适合脚本
bash run-jira.sh issue list -p DEMO --json
bash run-jira.sh issue list -p DEMO --plain --no-headers

# 将当前页导出为 CSV；也可使用已安装的 jira issue list --csv
bash run-jira.sh issue list -p DEMO --csv > issues.csv
bash run-jira.sh issue list -p DEMO --csv --no-headers

# 下一页必须复用相同的查询、排序及 --limit
# Cloud：使用上一页 nextPageToken
bash run-jira.sh issue list -p DEMO --limit 20 --next-page-token 'TOKEN'
# Data Center：使用上一页提示的偏移量
bash run-jira.sh issue list -p DEMO --limit 20 --start-at 20
```

`list` 由独立的 `JiraIssueListCommand` 实现。支持项目、状态、类型、优先级、经办人、报告人、标签，以及创建/更新时间下界筛选。多个条件以 `AND` 组合，重复 `-l/--label` 表示必须具备所有指定标签；状态、类型、优先级可用 `~` 前缀排除。经办人使用 Cloud `accountId` 或 Data Center 用户名；`me` 表示当前用户，`x` 表示未指派，`~x` 表示已指派；报告人也支持 `me`。字段名称和日期解释以 Jira 实例为准，普通筛选值会转义为 JQL 字符串，不能通过筛选值注入额外查询。

默认按 `created DESC` 排序；`--order-by` 支持 `created`、`updated`、`priority`、`key`、`rank`、`summary`、`status`、`assignee`、`issuetype`，`--reverse` 改为升序。`--created` 与 `--updated` 接受 Jira 日期、相对时间或 `day`/`today`、`week`、`month`、`year`，表示大于等于该时间或当前周期起点；`--created-before` 与 `--updated-before` 指定不包含边界的上限。`-w/--watching` 查询自己关注的问题，`--history` 使用 Jira 的最近浏览记录，`-R/--resolution` 筛选解决结果并支持 `~` 排除。**仅在没有默认项目、任何筛选及 JQL 时**默认添加 `created >= "-30d"`，以满足 Cloud 搜索必须具有查询限制的要求。`-q/--jql` 是完整查询，不能与显式筛选、`--order-by` 或 `--reverse` 混用，也不注入默认项目；Epic/Sprint 的范围限制仍会合并到自定义 JQL，保留其排序。

默认表格列为问题 key、类型、状态、优先级、经办人、所属 Epic key、标题。`--columns key,created,reporter` 可按顺序选择列，还支持 `updated`、`resolution`、`labels`、`components`、`fixVersions`、`parent`、`epic`；仅请求显示所需字段。缺失或不可见的可选字段显示 `-`，表格和 TSV 中的控制字符会替换为空格。`--plain` 输出 TSV；`--csv` 输出 UTF-8 CSV，所有字段加双引号，双引号加倍，并保留逗号和换行。`--csv` 与 `--plain` 互斥。`--no-headers` 移除表头，空结果没有数据行。`--raw` 与 `--json` 等价，输出包含上述支持字段及分页元数据的原始 JSON 页；不能与 `--plain`、`--csv` 或 `--no-headers` 混用。

`EPIC` 列显示直接关联的 Epic key：Data Center 动态发现 Epic Link 自定义字段；Cloud 使用 `parent`，仅在父问题类型的 `hierarchyLevel = 1`（无层级信息时类型名为 `Epic`）时显示，不把普通父任务当作 Epic。未关联、字段缺失或父类型信息不足时显示 `-`；不向上遍历子任务的父链。表格、TSV、CSV 均支持 `--columns key,epic,summary`。仅在请求 Epic 列或原始 JSON 时发现 Data Center 的 Epic Link 字段；原始 JSON 保留 `parent` 和动态发现的自定义字段，不添加合成 `epic` 属性。

每次调用仅请求一页，不自动遍历所有问题；`--limit` 默认为 50，范围为 1–100，Jira 仍可能返回更少的条目。在所选 API 版本下，Cloud 使用 `POST /search/jql` 与不透明的 `nextPageToken`；Data Center 使用 `POST /search` 与零起始的 `startAt`。两种分页参数不能跨模式使用。表格/TSV/CSV 模式在存在下一页时向标准错误输出续页提示，不混入标准输出的数据；JSON 模式直接保留服务器分页信息。查询结果受 Jira 权限限制，Cloud 最近写入的修改也可能不会立即出现在搜索中。

### 查看、关联、克隆与删除

```bash
bash run-jira.sh issue view DEMO-1 --comments 5
bash run-jira.sh issue view DEMO-1 --description-format raw --comments 0
bash run-jira.sh issue view DEMO-1 --plain
bash run-jira.sh issue link DEMO-1 DEMO-2 Blocks --no-input
bash run-jira.sh issue link remote DEMO-1 https://example.com "Reference" --no-input
bash run-jira.sh issue unlink DEMO-1 DEMO-2 --no-input
bash run-jira.sh issue clone DEMO-1 -s "Cloned summary" -y High -a me \
  -l backend -C Backend --fix-version 1.0 -H "old:new" --no-input
bash run-jira.sh issue delete DEMO-1 --cascade --no-input
```

`view` 直接在终端输出描述、关联问题和最近评论，不启动分页器。description 默认转换为 Markdown：v2 将 Jira Wiki 的标题、强调、列表、链接、代码、引用及表格转换为 Markdown；v3 将 ADF 的对应节点及标记转换为 Markdown。`--description-format markdown` 可显式选择默认模式，`--description-format raw` 则保留 v2 原始 Wiki 文本或 v3 原始 ADF JSON，不转换 description；`--plain` 保持以前的文本展示，不能与 `--description-format` 混用。评论的展示方式不变。Markdown 展示为只读近似转换，未知节点保留可读内容或类型提示，不要求满足无损导出限制，也不影响导出/回传规则。`--comments` 默认 1，范围为 0–1000；`--raw/--json` 仍输出整份原始问题，而不是仅改变 description 格式。

`link` 从 Jira 获取可用关联类型，支持类型名称或方向说明；`link remote` 添加外部网页链接。`unlink` 只删除指定两个问题之间实际存在的关联，若存在多个关联会逐个删除；多次请求不是原子操作，失败后应重新查询。`clone` 根据创建元数据复制可创建字段，不复制状态、历史或服务端管理字段；支持标题、优先级、经办人、标签、组件和修复版本覆盖。`-H/--replace old:new` 对标题及描述中的文本做区分大小写的替换。无法表示的可选自定义字段会计数警告后省略，缺少无默认值的必填字段则拒绝克隆；克隆不复制附件、评论或关联。

这些命令缺少位置参数时可在终端补问；`--no-input` 禁止提示。**显式提供问题 key 的 `delete` 会直接删除**，`--cascade` 同时删除子任务；请在测试项目确认权限，谨慎使用。命令不会实际调用 Jira 管理界面的跨项目迁移功能。

### 评论与工时

```bash
bash run-jira.sh issue comment add DEMO-1 "**Comment**" --no-input
bash run-jira.sh issue comment add DEMO-1 --template comment.md --no-input
printf 'Piped comment' | bash run-jira.sh issue comment add DEMO-1 --no-input
bash run-jira.sh issue comment add DEMO-1 "Internal note" --internal --no-input
bash run-jira.sh issue worklog add DEMO-1 "2d 3h 30m" \
  --comment "Implementation" --no-input
bash run-jira.sh issue worklog add DEMO-1 "10m" \
  --started "2026-11-05T09:00:00+08:00" --no-input
```

评论正文优先级为位置参数、`--template`（`-` 表示标准输入）、自动管道输入或终端提示；位置参数存在时不读取模板。`--internal` 写入 Jira Service Management 的内部评论属性，实际支持及权限由部署决定。评论和工时备注都支持 `--body-format markdown|wiki`，Wiki 仅适用于 v2。工时使用 Jira 的 `w`、`d`、`h`、`m` 格式，不假定每天工时长度；未提供 `--started` 时取当前时间。没有提供的工时备注不会被自动补问。

### Epic、Sprint 与目录命令

```bash
bash run-jira.sh epic list --table --plain
bash run-jira.sh epic list DEMO-10 -a x --plain
bash run-jira.sh epic create -n "Epic name" -s "Epic summary" --no-input
bash run-jira.sh epic add DEMO-10 DEMO-1 DEMO-2 --no-input
bash run-jira.sh epic remove DEMO-1 DEMO-2 --no-input
bash run-jira.sh sprint list --board-id 1 --state future,active --table
bash run-jira.sh sprint list --current --board-id 1 -a me --plain
bash run-jira.sh sprint list --prev --board-id 1 --plain
bash run-jira.sh sprint list --next --board-id 1 --plain
bash run-jira.sh sprint list 7 --plain --columns key,summary
bash run-jira.sh sprint add 7 DEMO-1 DEMO-2 --no-input
bash run-jira.sh project list --plain
bash run-jira.sh board list --project DEMO --plain
bash run-jira.sh release list --project DEMO --plain
bash run-jira.sh me
bash run-jira.sh open DEMO-1 --print
bash run-jira.sh open
bash run-jira.sh completion bash
bash run-jira.sh completion zsh
```

Epic 查询复用问题筛选和列选项，但不能指定问题类型；指定 Epic key 时列出其成员。Cloud 使用 `parent` 查询，Data Center 动态发现 Epic Link 字段；创建同样动态发现 Epic Name 字段，不硬编码自定义字段编号。Cloud 没有 Epic Name 字段时使用名称作为标题，冲突时拒绝；Data Center 缺少该字段则报错。Epic 和 Sprint 成员写入使用 Jira Software Agile API，每次接受 1–50 个不重复的问题 key 或 ID，受实例功能和权限限制。

Sprint 显式 ID 或 `--current`、`--prev`、`--next` 复用问题筛选；无选择器时列出 Sprint 元数据，默认最近 25 个，支持 `--state`、`--columns`、`--plain`、`--raw/--json`、`--no-headers`。`--board-id/--board` 显式指定 Scrum 看板；省略时仅在默认项目唯一匹配一个 Scrum 看板时自动选择。多个活动或未来 Sprint 要求显式 ID；`--prev` 根据完成时间选择最近关闭的 Sprint。元数据查询会遍历看板及 Sprint 页以确定唯一性和最近记录，再本地分页；筛选结果中的问题仍使用问题查询的分页机制。Sprint 元数据不支持 CSV。

`project list`、`board list`、`release list` 支持普通表格、TSV、自选列、原始 JSON 和分页；Cloud 使用分页接口，Data Center 的项目及版本数组在本地分页。`release list` 默认使用配置的项目。`me` 只输出 Cloud `accountId` 或 Data Center 用户名，便于 `-a "$(bash run-jira.sh me)"`。`open` 默认打开浏览器，失败则打印 URL；`--print/--print-url` 只输出链接，省略 key 时使用配置项目。`completion` 不需要配置或令牌，输出可供 Bash 或 Zsh 的 `bashcompinit` 加载的补全脚本。

### Markdown 边界

以下默认 Markdown 的规则不包含 `issue create --template` 和 `issue edit --template` 的自动后缀选择；这两种来源使用上文的 `--template-format` 规则。

API v3 描述、评论使用 ADF，支持普通段落、1–6 级标题、简单列表、无语言标注代码块，以及文本、加粗、斜体、行内代码、HTTP(S) 链接。复杂或未知 ADF 节点、富文本标记、嵌套列表及无法无损表示的内容会拒绝导出；编辑后的不支持语法会拒绝上传，而不是静默丢弃。

API v2（包括显式选择 v2 的 Cloud）使用 Jira Wiki 格式而非 ADF。创建、直接编辑和新增评论默认以 Markdown 解析正文；支持段落、标题、加粗、斜体、行内代码、带或不带语言的代码块、简单及嵌套列表、引用、链接、图片、分隔线和 GFM 表格，并转换为 Wiki。表格表头使用 `||`，数据单元格使用 `|`；支持单元格内的强调、行内代码、链接和转义竖线，保留空单元格，但不保留列对齐标记。HTML（包括表格中的 `<br>`）、带标题的链接/图片、多段落列表项及无法安全转换的内容会明确报错；不提供任务列表和删除线扩展转换。API v3 暂不支持 ADF 表格上传，Markdown 表格会明确报错。需要直接使用 Wiki 表格、宏、用户提及等原生格式时，添加 `--body-format wiki`，正文将原样提交，不进行 Markdown 解析或格式猜测。`*文字*` 在默认 Markdown 模式下为斜体，在 Wiki 模式下为加粗。Wiki 模式只适用于 API v2。

```bash
# .md / .markdown 后缀自动识别 Markdown，不区分大小写
bash run-jira.sh issue create -p DEMO -t Task -s "Markdown description" --template template.md --no-input
# 创建和直接编辑时其他后缀自动按 Wiki 提交；也可显式覆盖格式
bash run-jira.sh issue create -p DEMO -t Task -s "Wiki description" --template template.wiki --no-input
bash run-jira.sh issue create -p DEMO -t Task -s "Markdown description" --template template.txt --template-format markdown --no-input
bash run-jira.sh issue edit DEMO-1 --template template.wiki --no-input
# 评论仍需显式指定 Wiki
bash run-jira.sh comment DEMO-1 --file template.wiki --body-format wiki
```

v2 的 `export` 和 `comment-export` 在 `.md` 文件中保留原始 Wiki 文本，不执行有损的 Wiki → Markdown 转换，并在 HOCON 元数据中记录 `bodyFormat = "wiki"`；v3 记录 `"markdown"`。`update`、`issue edit --file` 和 `comment-update` 默认使用导出的格式，也可通过 `--body-format markdown|wiki` 显式覆盖。旧导出文件没有该字段时仍默认为 Markdown。切换格式时应重写正文，而不是只修改格式标记。其他可编辑字段保留为原始结构，不将插件字段强行转换成 Markdown。附件大小限制为 25 MB。

## English

[Project home](../../readme.MD) | [Architecture](../../docs/devpulse-plugins/jira/architecture.md)

`devpulse-jira` is an independently executable `jira` child module under the `devpulse-plugins` Maven aggregator and a Jira REST client for Jira Cloud (REST v3 by default) and Data Center (REST v2 by default), with an explicit API version override. The core `devpulse` neither loads nor depends on it; the Jira JAR itself does not need Bash. From the repository root, `mvn clean package` produces `devpulse-plugins/jira/target/devpulse-jira.jar`; `bash run-jira.sh` builds only that module and launches it. **It has not been validated against a live Jira instance**; first check field shapes, permissions, and workflows in a test project.

### Connection configuration and authentication

Connection configuration uses UTF-8 HOCON and is separate from issue templates passed to commands such as `issue create --file`. Lookup order is: an explicit `-c/--config` file, `local_jira_config.conf` in the **current working directory**, then `local_jira_config.conf` in the user's home `.jira` directory. A whole file is selected, not merged with other files. A missing explicit file or invalid local file fails instead of being skipped in favor of global configuration.

```hocon
url = "https://example.atlassian.net"
mode = "cloud"                  # Use "dc" for Data Center
email = "user@example.com"       # Required for Cloud; optional for Data Center
project = "DEMO"                # Default project key for create/list
# apiVersion = 3                # Optional: 2 or 3; defaults to Cloud=3, Data Center=2
```

Only the authentication token comes from the environment; never put it in connection configuration or issue templates:

```bash
export JIRA_API_TOKEN='your-api-token'          # Cloud API Token / Data Center PAT
```

Use global `-c/--config` to select another connection file. It can appear before or after subcommands; relative paths still resolve against the current working directory:

```bash
bash run-jira.sh --config connection.conf issue list -p DEMO
bash run-jira.sh issue list -p DEMO --config connection.conf
```

`url` and `mode` are required; `mode` accepts only `cloud` or `dc`, and Cloud additionally requires `email`. Cloud uses Basic authentication with email and token; Data Center uses Bearer authentication with a PAT. Only `JIRA_API_TOKEN` is read from the environment; the old `DEVPULSE_JIRA_*` variables are no longer used. Connection configuration accepts only `url`, `mode`, `email`, `apiVersion`, and `project`; a `token` field, HOCON `include`, and environment/JVM system-property substitutions are not supported. Help, version, connection initialization, and offline issue-template generation need neither existing connection configuration nor a token. The old `.jira.conf` is no longer discovered automatically; rename it to `local_jira_config.conf` or select it explicitly with `--config`.

`project` is the default project key and is required by the initialization wizard; older handwritten configurations can omit it to preserve prior behavior. For `issue create`, precedence is explicit `-p/--project`, issue-template `fields.project`, then connection-configuration `project`. `issue list` uses explicit `-p` when supplied, otherwise the default project. Complete `--jql` queries do not receive an implicit project condition.

`apiVersion` optionally accepts the number `2` or `3`. Omission preserves the original rule: `mode = "cloud"` selects 3, and `mode = "dc"` selects 2. An explicit value overrides that default, using `/rest/api/{apiVersion}/` paths and API v2 text or API v3 ADF for descriptions/comments. Authentication, user identifiers, and search pagination still follow `mode`. Selecting a version does not guarantee server support; for example, Data Center typically does not expose v3, so verify the deployment's available APIs.

Use HTTPS; HTTP is allowed only for local testing. Do not commit shell history containing credentials, environment dumps, exported Jira data, or attachments. HTTP failures display only the status code, not the Jira response body.

### CLI-provided requirements workflow skill

The JAR bundles the `jira-requirements` skill, execution guide, specification template, and Jira description template. The CLI only exports assets offline and performs Jira operations. The AI loading the skill analyzes requirements, writes specifications, and fills templates; the CLI does not call an LLM.

```powershell
# Export to a new project skill directory; no connection configuration or token needed
jira skill export --output .github\skills\jira-requirements

# English is the default; explicitly select Chinese when needed
jira skill export --output chinese-skill --language zh

# Select a template directory explicitly; --machine reports files and template sources
jira --machine skill export --output exported-skill --templates team-templates
```

Exports contain `SKILL.md`, `references\workflow.md`, `templates\specification.md`, and `templates\jira-description.md` for placement in a skill directory supported by the chosen AI tool. Bundled `SKILL`, `workflow`, and `jira-description` each have separate `.en.md` and `.zh.md` sources, exported under the standard filenames above. `--language en|zh` selects instruction and default-template body language, defaulting to English rather than bilingual output; skill metadata identifiers and trigger descriptions remain stable. Nothing is automatically installed into personal home, loaded, or executed. The destination must not exist, even as an empty directory; customized files are never overwritten. Export to a new directory and compare when upgrading.

By default, same-named `specification.md` and `jira-description.md` in `.jira\templates` under the **current working directory** override bundled templates, independently of connection-file location. `--templates DIR` selects only that override directory, without mixing in the default project directory. Either file can be supplied alone; absent files use bundled defaults in the selected language. Existing invalid, blank, non-UTF-8, or over-1-MiB templates fail rather than silently falling back; other files are ignored. Custom templates are copied unchanged without translation, placeholder interpretation, or instruction execution. The AI fills template bodies according to project language conventions instead of uploading instructions or fences.

The skill defines two business workflows. New requirements are clarified into a specification and projected into a Jira description. After the user confirms the target and concrete creation content, create directly, retain the key, and verify without additional terminal approval. For existing Jira issues, first read, strictly export, and retain a baseline; the AI reviews and prepares specification/description diffs. **After the user confirms the specification and concrete diff, synchronize directly** using the exported update-time check, without `--force`. Changed specifications, targets, or content require renewed confirmation.

These are skill collaboration rules, not new CLI authorization. Tool approvals and Jira permissions remain effective; the CLI adds no separate terminal approval. Exports do not bind server identity, so the skill requires the connection, principal, and Issue identity to remain consistent. Lossless-export failures, conflicts, and unknown write outcomes stop for reconciliation rather than automatic overwriting or recreation. Existing `issue template` remains the HOCON/Markdown issue-file generator, distinct from these business-description templates.

### AI and script invocation: `--machine`

Global `--machine` can appear before or after subcommands and enables a versioned JSON protocol. Without it, existing human interaction and `--json` output remain unchanged.

```bash
jira --machine issue list -p DEMO --limit 20
jira issue view DEMO-1 --machine
# Write examples: execute only after the caller has obtained user approval
jira --machine issue create -p DEMO -t Task -s "Approved task"
jira --machine issue edit DEMO-1 -s "Approved summary"
jira --machine issue comment add DEMO-1 "Approved comment"
```

Each invocation writes one JSON object to stdout with `schemaVersion: 1` and `ok`. Success includes `data` and `messages`. Queries already supporting `--json` automatically select raw JSON, preserving pagination. Creation returns Jira's creation result including `key`; edits and comments return `{key, changed}`; `me` returns an account identifier object; `open` returns `{url}` without launching a browser. Other commands currently return `data: null` and human text in `messages`; help and version also use text messages. **Do not parse messages as stable machine fields.**

```json
{"schemaVersion":1,"ok":true,"data":{"key":"DEMO-1","changed":true},"messages":[]}
```

Machine mode forces noninteractive execution and disables implicit stdin reads. Missing creation descriptions or comment bodies do not wait on an open input pipe. Explicitly use `--template -` for stdin, selecting `--template-format markdown|wiki` for creation/editing as appropriate. Missing required values fail immediately. Queries still fetch one page without following cursors automatically. Do not mix conflicting output flags such as `--plain` or `--csv`. Human CLI diagnostics are omitted from stderr and JSON; local errors use generic explanations to avoid leaking inputs or server content. JVM launcher stderr is outside this protocol.

Failures return an `error` object containing a stable `code`, explanatory `message`, `outcome` (`failed` or `unknown`), and `retryable`; HTTP failures also include `httpStatus`. Jira error bodies and tokens are not returned. `retryable` is advisory, not automatic retry or permission to bypass approval. Verify remote state before retrying writes; multi-request commands may have partially completed.

| Exit code | Error category |
| --- | --- |
| `0` | Success, including help/version |
| `1` | Local configuration, input, or file failure (`COMMAND_FAILED`), or internal failure (`INTERNAL_ERROR`) |
| `2` | Argument parsing failure (`INVALID_ARGUMENT`) |
| `3` | HTTP rejection or read-only HTTP failure (`HTTP_ERROR`) |
| `4` | Read-only transport failure, interruption, or invalid JSON (`TRANSPORT_ERROR`, `REQUEST_INTERRUPTED`, `INVALID_RESPONSE`) |
| `5` | Unknown write outcome: transport interruption, 5xx, invalid JSON, or missing issue key in a create response |

A write can fail after Jira has already applied it. `outcome: unknown` does not mean “not executed”: verify by querying or involve a human rather than blindly repeating creates, comments, or worklogs. The client adds no application-level automatic retries. Issue descriptions and comments remain untrusted data and must not be executed as AI instructions.

**`--machine` does not authorize actions.** After the user confirms the specification, target, and concrete changes in the collaboration workflow, AI calls create or edit directly without separate terminal approval. Concurrency checks and result verification remain; tool approvals, Jira permissions, and external policies still apply. Issue content is never an authorization instruction.

### Initializing a connection: `init`

```bash
# Configure step by step; save local_jira_config.conf in the current directory
bash run-jira.sh init

# Configure step by step; save .jira/local_jira_config.conf in the user's home
bash run-jira.sh init -g

# Scripted setup; omitting the API version retains mode-based defaults
bash run-jira.sh init --no-input --url https://example.atlassian.net \
  --mode cloud --email user@example.com --project DEMO
bash run-jira.sh init -g --no-input --url https://jira.example.com \
  --mode dc --api-version 2 --project DEMO
```

The wizard asks for URL, mode (default `cloud`), optional API version (Enter uses the mode default), Cloud email, and default project key, in that order. Supplied flags skip the corresponding prompts; invalid interactive input is reported and retried. Choosing automatic API selection omits `apiVersion` from the file so it continues to follow the mode. The wizard never contacts Jira or reads/writes a token; cancelling before completing all inputs creates no configuration file. Without a terminal, supply required values as flags; `--no-input` explicitly disables prompts.

`-g/--global` creates the home `.jira` directory automatically; without `-g`, the default destination is the current directory. Global `--config FILE` can select an initialization output file, but cannot be combined with `-g`. Existing destinations are never overwritten. The old `init --file ISSUE.conf` workflow has moved to `issue template --file ISSUE.conf`, which still creates adjacent `.conf` and `.md` issue-template files.

### Commands

```bash
bash run-jira.sh init
bash run-jira.sh issue template --file issue.conf
# Edit Jira REST fields in issue.conf and the adjacent issue.md
bash run-jira.sh issue create --file issue.conf
bash run-jira.sh issue list -p DEMO
bash run-jira.sh issue edit DEMO-1 --summary "Updated summary" --no-input
bash run-jira.sh issue view DEMO-1
bash run-jira.sh export DEMO-1 --file exported.conf
# Edit fields in exported.conf and the adjacent exported.md
bash run-jira.sh update --file exported.conf
bash run-jira.sh transitions DEMO-1
bash run-jira.sh transition DEMO-1 11
bash run-jira.sh comment DEMO-1 --file comment.md
bash run-jira.sh comment-export DEMO-1 123 --file existing-comment.md
# Edit existing-comment.md before uploading
bash run-jira.sh comment-update DEMO-1 123 --file existing-comment.md
bash run-jira.sh attach DEMO-1 --file diagram.png
```

`init`, `issue template`, and `export` create new files only; none overwrites existing files. Creation requires project, type, and summary; the connection configuration can supply the project. File-mode descriptions come from adjacent `.md` files. Export includes only fields reported editable by Jira `/editmeta`, plus the description; other fields are never uploaded. Custom fields retain their original JSON/HOCON structure and must follow the REST field format of the particular Jira instance. `update` compares fields with the exported baseline and sends only changes; edited descriptions are converted from Markdown to Jira content. Export again after a successful update.

Before upload, `update` and `comment-update` compare Jira's current `updated` value with its value at export, refusing stale changes by default; `--force` explicitly bypasses this check. Comment export creates a `.md` file and adjacent `.conf` metadata; re-export after a successful update. **This preflight check is not an atomic concurrency lock**: a write between checking and updating can still race. Top-level `transition` uses the **transition ID** returned by `transitions`; `issue move` also accepts transition or destination status names. New comments, transitions, and attachments do not use the export file's concurrency check.

Issue and comment exports record the effective `apiVersion`, which must match the current configuration when updating. Re-export after switching versions; `--force` does not bypass this check. Older exports without that field retain the original interpretation: Cloud v3 or Data Center v2.

### Creating issues: `issue create`

Inspired by [JiraCLI](https://github.com/ankitpokhrel/jira-cli), creation supports prompts, direct flags, and the existing file workflow. The sole creation entry point is `issue create`; top-level `create` has been removed. The `issue` group also provides `edit`, `assign`, `move` (alias `transition`), `list`, `view`, `link`, `unlink`, `clone`, `delete`, `comment add`, `worklog add`, and `template`; existing top-level operations remain compatible. The reference tool's fullscreen TUI, navigation shortcuts, and pager are outside this implementation's scope.

```bash
# Interactively enter project key, issue type, summary, and optional single-line description
bash run-jira.sh issue create

# Non-interactive creation; type and priority names must match the Jira instance
bash run-jira.sh issue create --no-input \
  -p DEMO -t Bug -s "Fix login timeout" -y High \
  -l backend -l urgent -C Backend --fix-version v2.0 \
  --affects-version v1.0 -b "Login times out after 30 seconds."

# Read multiline descriptions from a UTF-8 file or stdin
bash run-jira.sh issue create --no-input \
  -p DEMO -t Task -s "Document deployment" --template description.md
printf 'Deployment steps.\n' | bash run-jira.sh issue create --no-input \
  -p DEMO -t Task -s "Document deployment" --template -

# Optional Epic; omit --epic or supply an empty string to skip
bash run-jira.sh issue create --no-input \
  -p DEMO -t Story -s "Add login flow" --epic DEMO-100

# Parent issue, self-assignment, and custom fields (raw REST IDs and instance-specific values)
bash run-jira.sh issue create --no-input \
  -p DEMO -t Sub-task -s "Add timeout handling" -P DEMO-1 -a me \
  --custom 'customfield_10001=5' \
  --custom 'customfield_10002={value="Backend"}'

# Keep the file workflow; explicit flags override corresponding template fields
bash run-jira.sh issue create --file issue.conf -s "Updated summary"
```

Project (`-p/--project`), type (`-t/--type`), and summary (`-s/--summary`) are required. They may also come from `--file`'s `fields`; project and type accept raw REST `id` objects. If no project flag or template field is supplied, the connection configuration's default project is used. In a terminal, remaining missing required values are prompted by default, followed by an optional description if no description source was supplied. `--no-input` disables all prompts; without a terminal, creation never waits for interactive input either, and missing required fields fail before contacting Jira. Creation submits directly without an additional confirmation; scripts should always specify `--no-input`.

`-y/--priority` sets the priority name. Repeat `-l/--label`, `-C/--component`, `--fix-version`, and `--affects-version` for multiple values; explicit lists **replace** the entire corresponding template list and are not comma-separated. `-P/--parent` sets the parent issue key, subject to Jira's project type and hierarchy. `-a/--assignee` accepts a Cloud `accountId` or Data Center username; special values are `me` to look up the current user, `x` to unassign, and `default` for the project's default assignee. Display-name fuzzy lookup is not supported.

`--epic KEY` optionally selects an Epic; omitting it or supplying an empty string adds no association. Terminal creation prompts for an Epic key after required fields; Enter skips it. There is no additional prompt with `--no-input`, file mode, an existing parent, or `epic create`. Cloud uses `fields.parent.key` and rejects a simultaneous template `parent`; Data Center discovers the Epic Link field from metadata, with an explicit Epic overriding that field from the template or `--custom`. Do not combine `--epic` with `--parent`. Jira ultimately validates the target Epic, hierarchy, and permissions; association is submitted in the same request as creation.

Description precedence is `-b/--body` or `--template`, then the `.md` adjacent to `--file`, then piped or interactive input; `--body` and `--template` are mutually exclusive. `--template -` explicitly reads stdin until EOF. Without another description source or a terminal, creation also reads stdin, supporting `printf 'Description' | bash run-jira.sh issue create -t Task -s Summary --no-input`. Empty stdin omits `description`; `--file` still requires adjacent Markdown unless an explicit source overrides it. Do not supply descriptions through `fields.description`.

For creation and direct editing, `--template-format auto|markdown|wiki` defaults to `auto`: `.md` / `.markdown` suffixes on `--template` files are matched case-insensitively and converted from Markdown. Other suffixes, extensionless files, and `--template -` are submitted unchanged as Wiki, without content guessing. Explicit `--template-format markdown` or `wiki` overrides suffix detection, requires `--template`, and cannot be combined with `--body-format`. For compatibility, explicit `--body-format` takes precedence over suffix detection when `--template-format auto` is used. Wiki requires API v2; with API v3, explicitly select Markdown for non-Markdown suffixes or stdin templates. This rule affects only `issue create --template` and `issue edit --template`; defaults for other description sources and comments are unchanged. `issue edit --file` still selects its format from export metadata.

Repeat `--custom customfield_ID=HOCON_VALUE` for strings, numbers, booleans, `null`, objects, or arrays. Double-quote strings containing spaces or special characters according to HOCON rules, and shell-quote the entire argument. Only raw `customfield_digits` identifiers are accepted, not display names; explicit values override matching file fields. Prefer `--file` for more complex fields. Local validation checks required fields and input shapes; Jira ultimately validates creation permissions, field availability, and additional required fields. Creation metadata discovery, editor integration, and interactive user/field selection are not currently provided. Description conversion remains subject to the Markdown limits below.

### Editing issues: `issue edit`

```bash
# With no edit flags, interactively enter summary, priority, assignee, and a single-line description
# Enter keeps each value; interruption or EOF does not submit
bash run-jira.sh issue edit DEMO-1

# Change only specified fields; supplying edits skips prompts for other fields
bash run-jira.sh issue edit DEMO-1 --no-input \
  -s "Fix intermittent login timeout" -y High -a me

# List flags add/remove values rather than replacing the entire existing list
bash run-jira.sh issue edit DEMO-1 --no-input \
  -l backend --label=-urgent -C Backend --component=-Frontend \
  --fix-version v2.0 --fix-version=-v1.0 \
  --affects-version v1.1 --affects-version=-v1.0

# Replace description from a UTF-8 file or stdin
bash run-jira.sh issue edit DEMO-1 --no-input --template description.md
printf 'Updated description.\n' | bash run-jira.sh issue edit DEMO-1 \
  --no-input --template -

# Unassign, set custom fields, or change type and parent
bash run-jira.sh issue edit DEMO-1 --no-input -a x \
  --custom 'customfield_10001=8' \
  --custom 'customfield_10002=null'
bash run-jira.sh issue edit DEMO-1 --no-input -t Sub-task -P DEMO-2

# Reuse the existing export/edit/update workflow; KEY must match the export
bash run-jira.sh export DEMO-1 --file exported.conf
# Edit exported.conf and the adjacent exported.md
bash run-jira.sh issue edit DEMO-1 --file exported.conf --no-input
```

`edit` is implemented in the separate `JiraIssueEditCommand` class. An issue key or ID is a required positional argument; with an export file, it must exactly match the file's `key`. Direct edits support summary (`-s`), type (`-t`), priority (`-y`), assignee (`-a`), parent (`-P`), description, and raw `customfield_digits` fields, using the same HOCON custom-value rules as creation. Assignees use Cloud `accountId` or Data Center usernames, with `me` for self-assignment and `x` to unassign; `default` and display-name lookup are not supported. Type and parent changes are subject to Jira permissions and hierarchy rules; this command does not move issues between projects or transition status.

Labels (`-l/--label`), components (`-C/--component`), fix versions (`--fix-version`), and affected versions (`--affects-version`) are repeatable: ordinary values add entries, and a `-` prefix removes them. Prefer equals syntax such as `--label=-old` to avoid option ambiguity. Values are not comma-separated. Duplicate operations in the same direction are deduplicated; adding and removing the same value fails before contacting Jira. Changes use Jira's `update` operations rather than reading and overwriting whole lists. Unspecified fields remain unchanged; use exported-file mode to explicitly clear or replace entire arrays.

`-b/--body` and `--template` are mutually exclusive; `--template -` explicitly reads stdin until EOF. Empty description text or an empty description file clears the remote description; omitting a description source leaves it unchanged. Empty interactive input means keep, not clear. Only invocations without edit flags prompt for summary, priority, assignee, and a single-line description; Enter keeps each field, and keeping everything performs no write. With `--no-input` or no terminal, edit flags or `--file` are required. Supplying edits submits directly without an additional confirmation.

`issue edit --template` uses the same automatic detection and explicit overrides via `--template-format` as creation; an empty file still clears the description. `--template-format markdown|wiki` cannot be used alone or with `--file`.

Direct editing reads the current issue, sends only explicit changes, and skips recognizable unchanged fields. Before writing, it checks `/editmeta` for field editability and the required `set`, `add`, or `remove` operations, then compares the issue's `updated` value again and refuses concurrent changes. `--force` bypasses only the timestamp check, not Jira field permissions. **The timestamp check is not an atomic concurrency lock**; a write can still race between checking and submission. Description conversion remains subject to the Markdown limits below.

`--file` accepts only an existing `export` file and reuses top-level `update`'s field-difference, mode, and exported-timestamp checks. It cannot be combined with any field edit flags. Export again after a successful update. Top-level `update` remains compatible; there is no top-level `edit` command.

### Assigning users: `issue assign`

```bash
# Prompt for the issue key and user; select by number for multiple matches
bash run-jira.sh issue assign
# Prompt only for the user
bash run-jira.sh issue assign DEMO-1
# Complete inputs submit directly when the user match is unique
bash run-jira.sh issue assign DEMO-1 "Jon Doe"
# Unassign or assign to yourself
bash run-jira.sh issue assign DEMO-1 x
bash run-jira.sh issue assign DEMO-1 me
```

Regular users are resolved through `/user/assignable/search` for the current issue, supporting display names and Data Center usernames. Cloud uses `query` and Data Center uses `username`; assignment sends `accountId` and `name`, respectively. Exact name or identifier matches take precedence; otherwise, the search must return a unique user. Multiple matches offer numbered selection when a terminal is available and `--no-input` is absent; otherwise ambiguity fails without silently choosing the first user. Searches are limited to 50 users; reaching that limit requires a more specific query to avoid assigning from truncated results.

`--no-input` explicitly disables prompts and requires both KEY and USER. Missing arguments without a terminal also fail before contacting Jira. `me` resolves the current user, while `x` unassigns using a null user identifier; neither searches users. This command uses the dedicated `PUT /issue/{key}/assignee`, leaves other fields untouched, and does not use exported-file concurrency checks. Jira ultimately validates assignment permissions and whether unassigned issues are allowed. End-of-input or interruption before selection does not submit changes.

### Transitioning issues: `issue move`

```bash
# Prompt for the issue key and select an available transition by number
bash run-jira.sh issue move
# With a key, prompt only for the transition; complete unique inputs submit directly
bash run-jira.sh issue move DEMO-1
bash run-jira.sh issue move DEMO-1 "In Progress" --no-input
# Transition name or ID; issue transition is an alias for the same command
bash run-jira.sh issue transition DEMO-1 11 --no-input
# Add a comment, set resolution, and assign in the same transition request
bash run-jira.sh issue move DEMO-1 Done -R Fixed -a me \
  --comment "**Completed**" --no-input
bash run-jira.sh issue move DEMO-1 Done --comment "*Completed*" \
  --body-format wiki --no-input
```

Here, move means a workflow state transition, **not migration between projects**. The command first reads `/issue/{key}/transitions?expand=transitions.fields`, matching exact transition ID, transition name, then destination status name in that priority order. Names are case-insensitive, not fuzzy-matched. Unavailable targets are rejected. Multiple matches at the same priority offer numbered selection in an interactive terminal; with `--no-input` or no terminal, use a unique transition ID instead. Menus display the transition name, ID, and destination status and never default to the first entry. Missing inputs without interactive access fail before contacting Jira. End-of-input or interruption before selection does not submit changes.

`--comment` accepts Markdown, converted to Wiki for v2 or ADF for v3, subject to the same conversion limits as descriptions. Native Wiki comments require explicit `--body-format wiki` and API v2. `-R/--resolution` matches a resolution name or ID against allowed values in transition metadata; without an allowed-values list, it submits a name for Jira to validate. `-a/--assignee` accepts a Cloud `accountId` or Data Center username, supports `me` for self-assignment and `x` to unassign, and does not support `default` or display-name search.

Extra fields must be exposed by the selected transition's metadata and support the corresponding `set` or `add` operation, or local validation rejects them. Resolution, assignee, and comment are submitted in one `POST /issue/{key}/transitions` request rather than separate edits or comment writes, avoiding those independent side effects if transition preparation fails. Jira validates other workflow-required fields, permissions, and final transition availability. Additional required fields are not prompted automatically, and transitions do not use exported-timestamp concurrency checks. Existing top-level `transitions KEY` and `transition KEY ID` remain compatible.

### Listing issues: `issue list`

```bash
# Use the configured project; without a default project or filters, search the last 30 days
# Return at most one page
bash run-jira.sh issue list
bash run-jira.sh issue list -p DEMO -s "In Progress" -a me
bash run-jira.sh issue list -p DEMO -t Bug -y High -l backend -l urgent
bash run-jira.sh issue list -p DEMO -s '~Done' -a x --created -7d
bash run-jira.sh issue list -p DEMO -r me --updated -1d \
  --order-by updated --reverse --limit 20

# Complete JQL (including sorting); cannot be mixed with filter or sort flags
bash run-jira.sh issue list --jql \
  'project = DEMO AND (status = Open OR priority = High) ORDER BY updated DESC'

# JSON preserves Jira pagination metadata; plain output is TSV for scripts
bash run-jira.sh issue list -p DEMO --json
bash run-jira.sh issue list -p DEMO --plain --no-headers

# Export the current page as CSV; installed tools also support jira issue list --csv
bash run-jira.sh issue list -p DEMO --csv > issues.csv
bash run-jira.sh issue list -p DEMO --csv --no-headers

# Reuse the same query, sorting, and --limit for the next page
# Cloud: use nextPageToken from the previous page
bash run-jira.sh issue list -p DEMO --limit 20 --next-page-token 'TOKEN'
# Data Center: use the offset suggested by the previous page
bash run-jira.sh issue list -p DEMO --limit 20 --start-at 20
```

`list` is implemented in the separate `JiraIssueListCommand` class. Filters cover project, status, type, priority, assignee, reporter, labels, and lower bounds on creation/update times. Conditions are combined with `AND`; repeating `-l/--label` requires every specified label. Prefix status, type, or priority with `~` to exclude it. Assignees use Cloud `accountId` or Data Center usernames; `me` means the current user, `x` means unassigned, and `~x` means assigned. Reporters also support `me`. Jira determines field names and date interpretation. Ordinary filter values are escaped as JQL strings and cannot inject additional query clauses.

Sorting defaults to `created DESC`. `--order-by` accepts `created`, `updated`, `priority`, `key`, `rank`, `summary`, `status`, `assignee`, or `issuetype`; `--reverse` switches to ascending order. `--created` and `--updated` accept Jira dates, relative times, or `day`/`today`, `week`, `month`, and `year`, selecting values at or after the given time or current period boundary. `--created-before` and `--updated-before` provide exclusive upper bounds. `-w/--watching` selects watched issues, `--history` uses Jira's viewing history, and `-R/--resolution` filters resolutions with optional `~` exclusion. **Only when no default project, filters, or JQL are supplied**, the command adds `created >= "-30d"` to satisfy Cloud's restricted-query requirement. `-q/--jql` is a complete query and cannot be combined with explicit filters, `--order-by`, or `--reverse`; it does not receive an implicit default project. Epic/Sprint scopes still merge with custom JQL while preserving its ordering.

Default table columns are issue key, type, status, priority, assignee, associated Epic key, and summary. `--columns key,created,reporter` selects ordered columns; `updated`, `resolution`, `labels`, `components`, `fixVersions`, `parent`, and `epic` are also supported. Only displayed fields are requested. Missing or inaccessible optional fields display `-`; control characters become spaces in table/TSV output. `--plain` emits TSV; `--csv` emits UTF-8 CSV with quoted fields, doubled embedded quotes, and preserved commas and line breaks. `--csv` and `--plain` are mutually exclusive. `--no-headers` removes headers; empty results have no data rows. Equivalent `--raw` and `--json` emit the original JSON page containing the supported fields above and pagination metadata; they cannot be combined with `--plain`, `--csv`, or `--no-headers`.

The `EPIC` column shows the directly associated Epic key. Data Center dynamically discovers the Epic Link custom field; Cloud uses `parent`, displaying it only when its issue type has `hierarchyLevel = 1` (or is named `Epic` when hierarchy data is absent), never treating an ordinary parent task as an Epic. Unassociated issues, missing fields, or insufficient parent-type data display `-`; subtask parent chains are not traversed. Tables, TSV, and CSV support `--columns key,epic,summary`. Data Center Epic Link discovery runs only when the Epic column or raw JSON is requested. Raw JSON retains `parent` and the discovered custom field without adding a synthetic `epic` property.

Each invocation requests one page, never all issues automatically. `--limit` defaults to 50 and accepts 1–100; Jira may still return fewer items. Within the selected API version, Cloud uses `POST /search/jql` with an opaque `nextPageToken`; Data Center uses `POST /search` with zero-based `startAt`. Pagination flags cannot be used across modes. Table/TSV/CSV output writes a continuation hint to stderr when another page exists, keeping it separate from stdout data; JSON output preserves server pagination metadata directly. Results are limited by Jira permissions, and recent Cloud writes may not immediately appear in search results.

### Viewing, linking, cloning, and deleting

```bash
bash run-jira.sh issue view DEMO-1 --comments 5
bash run-jira.sh issue view DEMO-1 --description-format raw --comments 0
bash run-jira.sh issue view DEMO-1 --plain
bash run-jira.sh issue link DEMO-1 DEMO-2 Blocks --no-input
bash run-jira.sh issue link remote DEMO-1 https://example.com "Reference" --no-input
bash run-jira.sh issue unlink DEMO-1 DEMO-2 --no-input
bash run-jira.sh issue clone DEMO-1 -s "Cloned summary" -y High -a me \
  -l backend -C Backend --fix-version 1.0 -H "old:new" --no-input
bash run-jira.sh issue delete DEMO-1 --cascade --no-input
```

`view` prints descriptions, linked issues, and recent comments directly without a pager. Description output defaults to Markdown: v2 converts Jira Wiki headings, emphasis, lists, links, code, quotes, and tables; v3 converts corresponding ADF nodes and marks. `--description-format markdown` explicitly selects the default, while `--description-format raw` preserves the original v2 Wiki text or v3 ADF JSON without description conversion. `--plain` retains the previous text display and cannot be combined with `--description-format`. Comment display is unchanged. Markdown presentation is a read-only approximation: unknown nodes retain readable content or a type placeholder, without imposing lossless-export restrictions or changing export/upload rules. `--comments` defaults to 1 and accepts 0–1000. `--raw/--json` still outputs the entire original issue rather than changing only the description format.

`link` discovers available Jira link types and accepts type names or direction descriptions; `link remote` adds an external web link. `unlink` removes only actual links between the specified issues, removing each if multiple exist. Multiple requests are not atomic; query again after a failure. `clone` copies createable fields based on creation metadata rather than status, history, or server-managed fields, and supports summary, priority, assignee, label, component, and fix-version overrides. `-H/--replace old:new` replaces case-sensitive text in summary and description. Unsupported optional custom fields are omitted with a count warning; required fields without defaults cause cloning to fail. Attachments, comments, and links are not cloned.

Missing positional inputs can be prompted in a terminal; `--no-input` disables prompts. **`delete` with an explicit issue key deletes directly**; `--cascade` also deletes subtasks. Verify permissions in a test project and use it carefully. These commands do not implement Jira's administrative cross-project migration.

### Comments and worklogs

```bash
bash run-jira.sh issue comment add DEMO-1 "**Comment**" --no-input
bash run-jira.sh issue comment add DEMO-1 --template comment.md --no-input
printf 'Piped comment' | bash run-jira.sh issue comment add DEMO-1 --no-input
bash run-jira.sh issue comment add DEMO-1 "Internal note" --internal --no-input
bash run-jira.sh issue worklog add DEMO-1 "2d 3h 30m" \
  --comment "Implementation" --no-input
bash run-jira.sh issue worklog add DEMO-1 "10m" \
  --started "2026-11-05T09:00:00+08:00" --no-input
```

Comment source precedence is positional text, `--template` (`-` means stdin), then automatic piped input or a terminal prompt. Positional text prevents template reads. `--internal` sets Jira Service Management's internal-comment property; actual support and permissions depend on the deployment. Comments and worklog notes support `--body-format markdown|wiki`, with Wiki limited to v2. Worklogs use Jira's `w`, `d`, `h`, and `m` syntax without assuming a working-day length. `--started` defaults to now. Omitted worklog notes are not prompted automatically.

### Epics, sprints, and catalog commands

```bash
bash run-jira.sh epic list --table --plain
bash run-jira.sh epic list DEMO-10 -a x --plain
bash run-jira.sh epic create -n "Epic name" -s "Epic summary" --no-input
bash run-jira.sh epic add DEMO-10 DEMO-1 DEMO-2 --no-input
bash run-jira.sh epic remove DEMO-1 DEMO-2 --no-input
bash run-jira.sh sprint list --board-id 1 --state future,active --table
bash run-jira.sh sprint list --current --board-id 1 -a me --plain
bash run-jira.sh sprint list --prev --board-id 1 --plain
bash run-jira.sh sprint list --next --board-id 1 --plain
bash run-jira.sh sprint list 7 --plain --columns key,summary
bash run-jira.sh sprint add 7 DEMO-1 DEMO-2 --no-input
bash run-jira.sh project list --plain
bash run-jira.sh board list --project DEMO --plain
bash run-jira.sh release list --project DEMO --plain
bash run-jira.sh me
bash run-jira.sh open DEMO-1 --print
bash run-jira.sh open
bash run-jira.sh completion bash
bash run-jira.sh completion zsh
```

Epic listing reuses issue filters and columns except issue type, and lists members when an Epic key is provided. Cloud queries `parent`; Data Center discovers the Epic Link field dynamically. Creation similarly discovers Epic Name instead of hardcoding custom-field IDs. Without Epic Name, Cloud uses the name as summary and rejects conflicts; Data Center reports the missing field. Epic and Sprint membership writes use Jira Software's Agile API and accept 1–50 distinct issue keys or IDs, subject to deployment capabilities and permissions.

Explicit sprint IDs and `--current`, `--prev`, or `--next` reuse issue filters. Without a selector, Sprint metadata defaults to the latest 25 entries and supports `--state`, `--columns`, `--plain`, `--raw/--json`, and `--no-headers`. `--board-id/--board` explicitly selects a Scrum board; omission selects automatically only when the default project has exactly one matching Scrum board. Multiple active or future sprints require an explicit ID; `--prev` selects the most recently completed closed sprint. Metadata queries traverse board and sprint pages to establish uniqueness and recency, then paginate locally; issue results retain issue-search pagination. Sprint metadata does not support CSV.

`project list`, `board list`, and `release list` support tables, TSV, selected columns, raw JSON, and pagination. Cloud uses paginated APIs; Data Center project/version arrays are paginated locally. `release list` defaults to the configured project. `me` prints only the Cloud `accountId` or Data Center username for uses such as `-a "$(bash run-jira.sh me)"`. `open` launches a browser, printing the URL if unavailable; `--print/--print-url` only prints it, and an omitted key selects the configured project. `completion` requires no config or token and emits scripts for Bash or Zsh's `bashcompinit`.

### Markdown limits

The Markdown defaults below exclude automatic suffix selection for `issue create --template` and `issue edit --template`, which follow the `--template-format` rules above.

API v3 descriptions and comments use ADF and support paragraphs, headings (levels 1–6), simple lists, unlabelled code fences, text, bold, italic, inline code, and HTTP(S) links. Export refuses complex or unknown ADF nodes/marks, nested lists, and content that cannot be represented losslessly; unsupported edited syntax is rejected rather than silently dropped.

API v2, including Cloud explicitly configured for v2, uses Jira Wiki rather than ADF. Creation, direct editing, and new comments parse bodies as Markdown by default, converting paragraphs, headings, bold, italic, inline code, labelled or unlabelled code blocks, simple and nested lists, quotes, links, images, thematic breaks, and GFM tables to Wiki. Table headers use `||` and data cells use `|`; emphasis, inline code, links, escaped pipes, and empty cells are supported, but column alignment markers are not preserved. HTML (including `<br>` in tables), titled links/images, multi-paragraph list items, and content that cannot be converted safely produce explicit errors. Task-list and strikethrough extension conversion is not provided. API v3 does not yet support ADF table uploads and explicitly rejects Markdown tables. For native Wiki tables, macros, user mentions, and other native syntax, add `--body-format wiki` to submit the body unchanged, without Markdown parsing or format guessing. `*text*` means italic in default Markdown mode and bold in Wiki mode. Wiki mode requires API v2.

```bash
# .md / .markdown suffixes automatically select Markdown, case-insensitively
bash run-jira.sh issue create -p DEMO -t Task -s "Markdown description" --template template.md --no-input
# Other suffixes select Wiki for creation and direct editing; explicit overrides are available
bash run-jira.sh issue create -p DEMO -t Task -s "Wiki description" --template template.wiki --no-input
bash run-jira.sh issue create -p DEMO -t Task -s "Markdown description" --template template.txt --template-format markdown --no-input
bash run-jira.sh issue edit DEMO-1 --template template.wiki --no-input
# Comments still require explicit Wiki selection
bash run-jira.sh comment DEMO-1 --file template.wiki --body-format wiki
```

For v2, `export` and `comment-export` preserve the original Wiki text in the `.md` file rather than attempting a lossy Wiki → Markdown conversion, and record `bodyFormat = "wiki"` in HOCON metadata; v3 records `"markdown"`. `update`, `issue edit --file`, and `comment-update` use the exported format by default; `--body-format markdown|wiki` overrides it explicitly. Legacy exports without this field still default to Markdown. Rewrite the body when switching formats rather than merely changing the format marker. Other editable fields retain their raw structure instead of forcing plugin fields into Markdown. Attachments are limited to 25 MB.
