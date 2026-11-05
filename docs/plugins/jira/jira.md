# Jira 插件指南 / Jira plugin guide

[中文](#中文) | [English](#english)

## 中文

[项目首页](../../readme.MD)

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
bash run-jira.sh show DEMO-1
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

`update` 和 `comment-update` 上传前比较导出时和当前 Jira 的 `updated` 值，发现变化默认拒绝；`--force` 显式跳过检查。评论导出创建 `.md` 与同名 `.conf` 元数据，成功更新后需要重新导出。**这是上传前检查，不是原子并发锁**：检查与更新之间若发生并发写入仍可能冲突。状态更改使用 `transitions` 查到的**转换 ID**，不是状态名；新评论、状态转换和附件不使用导出文件的并发检查。

问题及评论导出会记录实际 `apiVersion`；更新时必须与当前配置一致，切换版本后需要重新导出，`--force` 不跳过此检查。没有该字段的旧导出按原规则解释为 Cloud v3 或 Data Center v2。

### 创建问题：`issue create`

借鉴 [JiraCLI](https://github.com/ankitpokhrel/jira-cli) 的创建体验，支持交互、直接参数与原有文件模式。创建入口统一为 `issue create`，顶层 `create` 已移除。当前 `issue` 分组提供 `create`、`edit`、`assign`、`list` 和 `template`，其余操作仍使用上面的顶层命令。

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

描述优先级为 `-b/--body` 或 `--template`、`--file` 同名 `.md`、交互输入；`--body` 与 `--template` 互斥。`--template -` 显式读取标准输入直至结束；其他模式不会自动消费管道。未提供描述的纯参数非交互创建不发送 `description` 字段；使用 `--file` 且未覆盖描述来源时仍要求相邻 `.md`，保持原行为。`fields.description` 不可用于传入描述。

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

直接编辑先读取当前问题，仅发送显式变更，跳过可识别的未变化字段；写入前通过 `/editmeta` 检查字段可编辑性及所需的 `set`、`add`、`remove` 操作，并再次比较问题的 `updated` 值，发现并发变化则拒绝提交。`--force` 只跳过更新时间检查，不绕过 Jira 字段权限。**更新时间检查不是原子并发锁**；检查与实际写入之间仍可能出现竞争。描述转换仍受下面的 Markdown 边界限制。

`--file` 仅接受已有 `export` 生成的文件，复用顶层 `update` 的字段差异、模式及导出时更新时间检查；不与任何字段编辑参数混用。成功更新后需要重新导出。顶层 `update` 保持兼容，不新增顶层 `edit`。

### 指派用户：`issue assign`

```bash
# 交互输入问题 key 和用户；多人匹配时按编号选择
bash run-jira.sh issue assign
# 仅补问用户
bash run-jira.sh issue assign DEMO-1
# 同时提供两个参数时跳过所有提示
bash run-jira.sh issue assign DEMO-1 "Jon Doe"
# 取消指派或指派给自己
bash run-jira.sh issue assign DEMO-1 x
bash run-jira.sh issue assign DEMO-1 me
```

普通用户通过当前问题的 `/user/assignable/search` 查询，支持显示名和 Data Center 用户名；Cloud 使用 `query`，Data Center 使用 `username`，写入时分别使用 `accountId` 和 `name`。优先采用名称或标识完全匹配的结果，否则要求搜索结果唯一。多人匹配时，仅在省略位置参数的交互模式中提供编号选择；两个参数齐全时直接报错，不偷偷选择第一人或弹出提示。单次查询最多 50 人，达到上限则要求缩小搜索范围，避免根据截断结果误指派。

`--no-input` 显式禁止交互，必须提供 KEY 和 USER；无终端且缺少参数时同样在请求 Jira 前报错。`me` 查询当前用户，`x` 通过空用户标识取消指派，均不搜索用户。命令使用专用 `PUT /issue/{key}/assignee`，不改动其他字段，也不使用导出文件的并发检查；Jira 最终校验指派权限及是否允许未指派。输入结束或选择前中断不会提交。

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

默认按 `created DESC` 排序；`--order-by` 支持 `created`、`updated`、`priority`、`key`、`rank`、`summary`、`status`、`assignee`、`issuetype`，`--reverse` 改为升序。`--created` 与 `--updated` 接受 Jira 日期或相对时间，表示大于等于该值。**仅在没有默认项目、任何筛选及 JQL 时**默认添加 `created >= "-30d"`，以满足 Cloud 搜索必须具有查询限制的要求；默认或显式项目等筛选存在时不再添加时间限制。`-q/--jql` 是完整查询，不能与显式筛选、`--order-by` 或 `--reverse` 混用，也不注入默认项目，Cloud 的自定义 JQL 同样需要包含查询限制。

默认表格列为问题 key、类型、状态、优先级、经办人、标题；缺失或不可见的可选字段显示 `-`，表格和 TSV 中的控制字符会替换为空格。`--plain` 输出制表符分隔内容；`--csv` 输出相同列的 UTF-8 CSV，所有字段用双引号包裹，字段内的双引号加倍，并保留逗号和换行，可重定向保存为文件。`--csv` 与 `--plain` 互斥。`--no-headers` 移除表格、TSV 或 CSV 的表头。空结果仅输出表头，配合 `--no-headers` 时无输出。`--raw` 与 `--json` 等价，输出包含 `issues` 和分页元数据的原始 JSON 页，字段限定为标题、状态、类型、优先级、经办人和更新时间；不能与 `--plain`、`--csv` 或 `--no-headers` 混用。

每次调用仅请求一页，不自动遍历所有问题；`--limit` 默认为 50，范围为 1–100，Jira 仍可能返回更少的条目。在所选 API 版本下，Cloud 使用 `POST /search/jql` 与不透明的 `nextPageToken`；Data Center 使用 `POST /search` 与零起始的 `startAt`。两种分页参数不能跨模式使用。表格/TSV/CSV 模式在存在下一页时向标准错误输出续页提示，不混入标准输出的数据；JSON 模式直接保留服务器分页信息。查询结果受 Jira 权限限制，Cloud 最近写入的修改也可能不会立即出现在搜索中。

### Markdown 边界

API v3 描述、评论使用 ADF，支持普通段落、1–6 级标题、简单列表、无语言标注代码块，以及文本、加粗、斜体、行内代码、HTTP(S) 链接。复杂或未知 ADF 节点、富文本标记、嵌套列表及无法无损表示的内容会拒绝导出；编辑后的不支持语法会拒绝上传，而不是静默丢弃。

API v2（包括显式选择 v2 的 Cloud）使用 Jira Wiki 格式而非 ADF。创建、直接编辑和新增评论默认以 Markdown 解析正文；支持段落、标题、加粗、斜体、行内代码、带或不带语言的代码块、简单及嵌套列表、引用、链接、图片和分隔线，并转换为 Wiki。HTML、带标题的链接/图片、多段落列表项及无法安全转换的内容会明确报错；不提供 GFM 表格、任务列表和删除线扩展转换。需要直接使用 Wiki 表格、宏、用户提及等原生格式时，添加 `--body-format wiki`，正文将原样提交，不进行 Markdown 解析或格式猜测。`*文字*` 在默认 Markdown 模式下为斜体，在 Wiki 模式下为加粗。Wiki 模式只适用于 API v2；API v3 保持 Markdown → ADF 的原有行为。

```bash
# 默认 Markdown，也可显式添加 --body-format markdown
bash run-jira.sh issue create -p DEMO -t Task -s "Markdown description" --template template.md --no-input
# 原生 Jira Wiki；文件扩展名不决定格式
bash run-jira.sh issue create -p DEMO -t Task -s "Wiki description" --template template.wiki --body-format wiki --no-input
bash run-jira.sh issue edit DEMO-1 --template template.wiki --body-format wiki --no-input
bash run-jira.sh comment DEMO-1 --file template.wiki --body-format wiki
```

v2 的 `export` 和 `comment-export` 在 `.md` 文件中保留原始 Wiki 文本，不执行有损的 Wiki → Markdown 转换，并在 HOCON 元数据中记录 `bodyFormat = "wiki"`；v3 记录 `"markdown"`。`update`、`issue edit --file` 和 `comment-update` 默认使用导出的格式，也可通过 `--body-format markdown|wiki` 显式覆盖。旧导出文件没有该字段时仍默认为 Markdown。切换格式时应重写正文，而不是只修改格式标记。其他可编辑字段保留为原始结构，不将插件字段强行转换成 Markdown。附件大小限制为 25 MB。

## English

[Project home](../../readme.MD)

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
bash run-jira.sh show DEMO-1
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

Before upload, `update` and `comment-update` compare Jira's current `updated` value with its value at export, refusing stale changes by default; `--force` explicitly bypasses this check. Comment export creates a `.md` file and adjacent `.conf` metadata; re-export after a successful update. **This preflight check is not an atomic concurrency lock**: a write between checking and updating can still race. Change status by the **transition ID** returned by `transitions`, not a status name. New comments, transitions, and attachments do not use the export file's concurrency check.

Issue and comment exports record the effective `apiVersion`, which must match the current configuration when updating. Re-export after switching versions; `--force` does not bypass this check. Older exports without that field retain the original interpretation: Cloud v3 or Data Center v2.

### Creating issues: `issue create`

Inspired by [JiraCLI](https://github.com/ankitpokhrel/jira-cli), creation supports prompts, direct flags, and the existing file workflow. The sole creation entry point is `issue create`; top-level `create` has been removed. The `issue` group currently exposes `create`, `edit`, `assign`, `list`, and `template`; other operations still use the top-level commands above.

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

Description precedence is `-b/--body` or `--template`, then the `.md` adjacent to `--file`, then interactive input; `--body` and `--template` are mutually exclusive. `--template -` explicitly reads stdin until EOF; other modes do not automatically consume pipes. Flag-only non-interactive creation without a description omits `description`; `--file` still requires adjacent Markdown unless an explicit description source overrides it, preserving existing behavior. Do not supply descriptions through `fields.description`.

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

Direct editing reads the current issue, sends only explicit changes, and skips recognizable unchanged fields. Before writing, it checks `/editmeta` for field editability and the required `set`, `add`, or `remove` operations, then compares the issue's `updated` value again and refuses concurrent changes. `--force` bypasses only the timestamp check, not Jira field permissions. **The timestamp check is not an atomic concurrency lock**; a write can still race between checking and submission. Description conversion remains subject to the Markdown limits below.

`--file` accepts only an existing `export` file and reuses top-level `update`'s field-difference, mode, and exported-timestamp checks. It cannot be combined with any field edit flags. Export again after a successful update. Top-level `update` remains compatible; there is no top-level `edit` command.

### Assigning users: `issue assign`

```bash
# Prompt for the issue key and user; select by number for multiple matches
bash run-jira.sh issue assign
# Prompt only for the user
bash run-jira.sh issue assign DEMO-1
# Supplying both arguments skips all prompts
bash run-jira.sh issue assign DEMO-1 "Jon Doe"
# Unassign or assign to yourself
bash run-jira.sh issue assign DEMO-1 x
bash run-jira.sh issue assign DEMO-1 me
```

Regular users are resolved through `/user/assignable/search` for the current issue, supporting display names and Data Center usernames. Cloud uses `query` and Data Center uses `username`; assignment sends `accountId` and `name`, respectively. Exact name or identifier matches take precedence; otherwise, the search must return a unique user. Multiple matches offer numbered selection only in interactive mode with positional arguments omitted. With both arguments supplied, ambiguity fails instead of silently choosing the first user or prompting. Searches are limited to 50 users; reaching that limit requires a more specific query to avoid assigning from truncated results.

`--no-input` explicitly disables prompts and requires both KEY and USER. Missing arguments without a terminal also fail before contacting Jira. `me` resolves the current user, while `x` unassigns using a null user identifier; neither searches users. This command uses the dedicated `PUT /issue/{key}/assignee`, leaves other fields untouched, and does not use exported-file concurrency checks. Jira ultimately validates assignment permissions and whether unassigned issues are allowed. End-of-input or interruption before selection does not submit changes.

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

Sorting defaults to `created DESC`. `--order-by` accepts `created`, `updated`, `priority`, `key`, `rank`, `summary`, `status`, `assignee`, or `issuetype`; `--reverse` switches to ascending order. `--created` and `--updated` accept Jira dates or relative times and mean greater than or equal to that value. **Only when no default project, filters, or JQL are supplied**, the command adds `created >= "-30d"` to satisfy Cloud's requirement for a restricted query; a default or explicit project or other filter removes this time restriction. `-q/--jql` is a complete query and cannot be combined with explicit filters, `--order-by`, or `--reverse`; it does not receive an implicit default project either. Custom Cloud JQL must also contain a search restriction.

Default table columns are issue key, type, status, priority, assignee, and summary. Missing or inaccessible optional fields display `-`; control characters are replaced with spaces in table/TSV output. `--plain` emits tab-separated values. `--csv` emits the same columns as UTF-8 CSV, quoting every field, doubling embedded double quotes, and preserving commas and line breaks; redirect it to save a file. `--csv` and `--plain` are mutually exclusive. `--no-headers` removes table, TSV, or CSV headers. Empty results print only headers, or nothing with `--no-headers`. `--raw` and `--json` are equivalent: they emit the original JSON page containing `issues` and pagination metadata, with issue fields limited to summary, status, type, priority, assignee, and update time. They cannot be combined with `--plain`, `--csv`, or `--no-headers`.

Each invocation requests one page, never all issues automatically. `--limit` defaults to 50 and accepts 1–100; Jira may still return fewer items. Within the selected API version, Cloud uses `POST /search/jql` with an opaque `nextPageToken`; Data Center uses `POST /search` with zero-based `startAt`. Pagination flags cannot be used across modes. Table/TSV/CSV output writes a continuation hint to stderr when another page exists, keeping it separate from stdout data; JSON output preserves server pagination metadata directly. Results are limited by Jira permissions, and recent Cloud writes may not immediately appear in search results.

### Markdown limits

API v3 descriptions and comments use ADF and support paragraphs, headings (levels 1–6), simple lists, unlabelled code fences, text, bold, italic, inline code, and HTTP(S) links. Export refuses complex or unknown ADF nodes/marks, nested lists, and content that cannot be represented losslessly; unsupported edited syntax is rejected rather than silently dropped.

API v2, including Cloud explicitly configured for v2, uses Jira Wiki rather than ADF. Creation, direct editing, and new comments parse bodies as Markdown by default, converting paragraphs, headings, bold, italic, inline code, labelled or unlabelled code blocks, simple and nested lists, quotes, links, images, and thematic breaks to Wiki. HTML, titled links/images, multi-paragraph list items, and content that cannot be converted safely produce explicit errors. GFM table, task-list, and strikethrough extension conversion is not provided. For native Wiki tables, macros, user mentions, and other native syntax, add `--body-format wiki` to submit the body unchanged, without Markdown parsing or format guessing. `*text*` means italic in default Markdown mode and bold in Wiki mode. Wiki mode requires API v2; API v3 retains its existing Markdown → ADF behavior.

```bash
# Markdown by default; --body-format markdown is also accepted
bash run-jira.sh issue create -p DEMO -t Task -s "Markdown description" --template template.md --no-input
# Native Jira Wiki; the file extension does not determine the format
bash run-jira.sh issue create -p DEMO -t Task -s "Wiki description" --template template.wiki --body-format wiki --no-input
bash run-jira.sh issue edit DEMO-1 --template template.wiki --body-format wiki --no-input
bash run-jira.sh comment DEMO-1 --file template.wiki --body-format wiki
```

For v2, `export` and `comment-export` preserve the original Wiki text in the `.md` file rather than attempting a lossy Wiki → Markdown conversion, and record `bodyFormat = "wiki"` in HOCON metadata; v3 records `"markdown"`. `update`, `issue edit --file`, and `comment-update` use the exported format by default; `--body-format markdown|wiki` overrides it explicitly. Legacy exports without this field still default to Markdown. Rewrite the body when switching formats rather than merely changing the format marker. Other editable fields retain their raw structure instead of forcing plugin fields into Markdown. Attachments are limited to 25 MB.
