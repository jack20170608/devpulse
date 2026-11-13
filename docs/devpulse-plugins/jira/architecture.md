# Jira 架构设计 / Jira architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../../architecture.md) | [使用说明](../../../devpulse-plugins/jira/README.md)

### 模块与分层

Jira 是独立的可执行工具，不依赖 core、Pulse 或 Gather，不需要 Bash 执行 JAR。`JiraCommand` 定义 picocli 命令树并协调连接、输入和输出；创建、编辑、查询、指派、流转、其他 Issue 操作及 Agile/目录命令分担在 `cli` 包的命令类中。

`JiraConfiguration` 负责 HOCON 连接配置发现与校验；`JiraClient` 负责 Java HTTP 请求、认证、REST 路径、字段元数据和分页。`JiraMarkdown` 处理上传及严格往返转换，`JiraDescriptionMarkdown` 负责只读查看时的近似 Markdown 展示，不能将展示结果的容错规则用于无损导出。

### 配置与协议边界

连接文件按显式 `--config`、当前工作目录、用户 home 的优先级选择整份文件，不合并配置。仅 token 从 `JIRA_API_TOKEN` 读取，不写入配置或 issue 模板；配置不支持 include 或系统属性、环境变量替换。

部署模式决定认证、用户标识与搜索分页：Cloud 使用邮箱/token Basic 认证、`accountId` 和 `nextPageToken`；Data Center 使用 PAT Bearer 认证、用户名和 `startAt`。API 版本单独控制 REST 路径及正文格式，默认 Cloud v3、Data Center v2，可显式覆盖；不保证服务器提供所选版本。

HTTP 客户端不跟随重定向，设置连接及请求超时；拒绝远程明文 HTTP，仅允许本机 HTTP 测试。失败只显示 HTTP 状态码，不输出 Jira 的错误正文或认证信息。

### 需求工作流 Skill 分发

`src/main/resources/skills/jira-requirements` 随 Jira JAR 打包，包含简短 Skill、执行/环境说明及规格书/Jira 描述模板。`SKILL`、`references/workflow`、`references/environment` 和 `templates/jira-description` 各分为 `.en.md` 和 `.zh.md`，规格书模板仍中英对照维护；`JiraSkillCommands` 通过 `skill export --language en|zh` 选择对应语言文件，规格书模板提取单一语言正文，默认英文，将完整资源包写入新目录。导出使用 `SKILL.md`、`references/workflow.md`、`references/environment.md` 等标准文件名。优先使用显式模板目录或当前工作目录 `.jira/templates` 的同名覆盖文件，缺失项回退所选语言的内置资源；自定义模板原样复制，不自动翻译。不修改已有安装、不调用 LLM；默认离线且不依赖连接配置或 token。

显式 `--with-environment` 启用 `JiraSkillEnvironment`，复用 `JiraClient` 只读采集目标项目的 Issue Type、Epic、可指派用户、看板和 Scrum Sprint，附加 `references/environment.json`。该模块集中管理 Cloud/DC 分页、重叠页检测、投影白名单及分节完整性，平台查询和 Agile 查询通过同一请求接口接入，不引入通用插件或新的工具模块。快照绑定采集时间、服务器、模式/API、账号和项目；分节失败记录脱敏状态，不输出服务端错误正文。Cloud 用户搜索窗口受限，不能宣称完整成员列表；Sprint 的可用性以查询成功为证据，不将 403/404 或没有可见 Scrum 看板等同于实例不支持。采集在写入目录之前完成，凭据和无关用户数据不落盘；Skill 使用时检查范围、完整性和时效，不将候选当作授权。

该 Skill 将分析和内容生成交给 AI：新需求规格及具体创建内容确认后直接创建，已有 Issue 严格导出基线，规格及差异确认后直接同步，并核验结果。覆盖文件只是格式输入，不是执行指令。此协作流程复用既有 create/export/edit 与机器协议，不增加授权状态机或独立终端审批，不替代工具权限；详细模板优先级、确认和故障处理维护在使用说明及资源包中。

### 机器调用协议

`JiraMachine` 作为 picocli 执行 Adapter，提供显式 `--machine`，不新建工具模块或复制业务操作。该模式强制无交互、显式 stdin 和不启动浏览器，通过版本化 JSON 信封返回结果；已有 JSON 查询复用原始结果，创建、编辑、评论及账户/链接查询提供结构化数据，其他命令暂保留非稳定的文本消息。人类诊断不混入协议。`JiraRequestException` 保留脱敏的 HTTP 状态、重试提示和写操作结果不确定性；不添加自动重试，不提供跨请求事务。具体退出码与数据契约见模块使用说明。

该模式只是机器接口，不代表写权限或用户确认。用户对规格及具体变更的确认由协作流程负责，CLI 直接执行写命令，不增加独立审批；并发检查与未知结果处理属于技术保护，不能替代凭据隔离。

### 编辑、导出与内容转换

`JiraIssueDocument` 集中管理单个 Markdown Issue 文件的 YAML frontmatter、正文、字段映射及基线检查；create/edit/template/export/update 共用这一格式，不再接受 HOCON Issue 文件。SnakeYAML Engine 解析 YAML 节点，拒绝重复键、别名、锚点、合并键、未知标签及过量输入，并直接保留十进制精度。连接和评论元数据仍使用 HOCON。

命令行参数优先于文件，空字符串或省略字段保留原值，显式 null 才清空；必填字段不能清空，状态等只读信息不自动回写。经办人使用 Cloud accountId 或 DC 用户名；Epic、Story Points 按实例元数据映射。导出将常用引用转换为稳定用户标识、key 或名称，自定义字段保留结构，并在同一 YAML 中保存 originalFields、描述哈希、模式、API 版本和正文格式。update 要求完整导出基线，复用 edit 的差异和可编辑操作检查。编辑校验文件与当前 updated，写入前再次检查；这是非原子检查，不能保证与其他客户端写入互斥。

v3 使用 ADF，上传和导出只支持严格的 Markdown 子集，不能无损表示的节点拒绝往返；暂不支持 ADF 表格上传。`JiraMarkdown` 使用 CommonMark 的 GFM 表格扩展解析 Markdown，v2 将表格转换为 Wiki 表头和数据行，不保留列对齐标记。v2 直接写入默认将 Markdown 转为 Jira Wiki，也可显式提交原生 Wiki；导出保留原始 Wiki，并记录格式，避免有损转换。查看命令的近似 Markdown 渲染只读，不改变导出或上传规则。

`issue view --format markdown` 使用同一文档序列化，将所选属性输出为 YAML frontmatter，正文复用只读 description 渲染，不查询评论。字段保留顺序；Epic 和 Story Points 共用按需元数据查询，不硬编码 ID。assignee 输出可导入的用户标识。view 文档可用于 create/edit，但没有严格更新基线，不能作为无损导出；未改变的展示正文不会回写。严格导出描述按哈希判断变更，未修改时不重新转换。机器模式通过 data.key/data.markdown 返回，不改变原有默认 JSON 查看协议。

评论、附件、工时、关联、克隆及状态流转由各自 API 执行，不共享导出文件的冲突检查；多请求操作不提供跨请求事务。完整命令、权限限制、分页与格式边界统一维护在模块使用说明中。

create/edit 的 `--template` 读取完整 YAML metadata + description，`--file` 是兼容别名；模板标准输入也要求相同结构。`--desc` 仅覆盖 description，不替换 metadata；`--desc -` 读取纯描述，与 `--template -` 不可同时使用。`DescriptionFormat` 为 create/edit/update 提供 `--desc-format`：显式格式优先，显式描述默认 Markdown，否则使用模板记录的格式；Wiki 仍要求 API v2。旧的 `--body`、`--body-format` 保留为兼容别名，YAML bodyFormat 不改名；评论、工时等继续使用独立的 `BodyFormat`。模板容器不需要格式选项，删除 `TemplateFormat` 和 `--template-format`，不再按后缀猜测正文格式。Epic 创建复用模板读取结果，避免 stdin 读取两次；评论的模板语义不变。

问题列表默认显示 `EPIC` key 列，复用列表的表格、TSV 和 CSV 渲染。Data Center 按字段元数据发现 Epic Link；Cloud 从带有 Epic 类型信息的 `parent` 读取，不逐项请求父问题，也不将普通父任务当作 Epic。仅在需要 Epic 信息时请求相关字段，原始 JSON 不添加合成字段。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugins/jira/README.md)

### Module and layers

Jira is a standalone executable that depends on neither core, Pulse, nor Gather and needs no Bash to run its JAR. `JiraCommand` defines the picocli command tree and coordinates connections, input, and output. Command classes in `cli` divide creation, editing, querying, assignment, transitions, other issue operations, and Agile/catalog commands.

`JiraConfiguration` discovers and validates HOCON connection configuration. `JiraClient` owns Java HTTP requests, authentication, REST paths, field metadata, and pagination. `JiraMarkdown` handles uploads and strict round-trip conversion; `JiraDescriptionMarkdown` handles approximate read-only Markdown presentation. Presentation fallback rules must not be applied to lossless exports.

### Configuration and protocol boundaries

Connection files are selected as a whole in priority order: explicit `--config`, current working directory, then user home. Files are not merged. Only the token comes from `JIRA_API_TOKEN` and is never written to configuration or issue templates. Configuration supports no includes or system-property/environment substitution.

Deployment mode determines authentication, user identities, and search pagination: Cloud uses email/token Basic authentication, `accountId`, and `nextPageToken`; Data Center uses PAT Bearer authentication, usernames, and `startAt`. API version separately determines REST paths and body format, defaulting to Cloud v3 or Data Center v2 with an explicit override. Availability of the selected API version is not guaranteed.

The HTTP client follows no redirects and sets connection and request timeouts. Remote plaintext HTTP is refused; only local HTTP testing is allowed. Failures display HTTP status codes rather than Jira error bodies or authentication information.

### Requirements workflow skill distribution

`src/main/resources/skills/jira-requirements` ships inside the Jira JAR, containing a concise skill, execution/environment guides, and specification/Jira description templates. `SKILL`, `references/workflow`, `references/environment`, and `templates/jira-description` each have separate `.en.md` and `.zh.md` sources; the specification template remains bilingual. Through `skill export --language en|zh`, `JiraSkillCommands` selects matching language files, extracts a single language body for the specification template, and writes the complete bundle into a new directory, defaulting to English. Exports retain standard filenames such as `SKILL.md`, `references/workflow.md`, and `references/environment.md`. Same-named overrides from an explicit template directory or the working directory's `.jira/templates` take precedence, with missing files falling back to the selected language's bundled resources. Custom templates are copied without translation. It modifies no existing installation and calls no LLM; default exports remain offline without requiring configuration or tokens.

Explicit `--with-environment` enables `JiraSkillEnvironment`, reusing `JiraClient` for read-only collection of project issue types, epics, assignable users, boards, and Scrum sprints into `references/environment.json`. The module centralizes Cloud/DC pagination, overlap detection, projection allowlists, and per-section completeness; platform and Agile queries share a request interface without adding generic plugins or tool modules. Snapshots identify capture time, server, mode/API, account, and project; section failures retain sanitized status rather than server error bodies. Cloud user-search limits preclude claims of complete member lists. Sprint availability requires successful queries: 403/404 responses or no visible Scrum board do not prove instance-wide absence. Collection precedes directory writes; credentials and unrelated user data are not persisted. The skill checks scope, completeness, and freshness instead of treating candidates as authorization.

The skill delegates analysis and content generation to AI: new requirements are created directly after the specification and concrete creation content are confirmed; existing issues retain strict export baselines and synchronize after specification/diff confirmation, followed by verification. Overrides provide formatting, not executable instructions. This collaboration workflow reuses existing create/export/edit operations and the machine protocol without adding an authorization state machine or separate terminal approval, or replacing tool permissions. Template precedence, confirmation, and failure handling live in the usage guide and bundled resources.

### Machine invocation protocol

`JiraMachine` is a picocli execution Adapter offering explicit `--machine` without adding a tool module or duplicating business operations. It forces noninteractive execution, explicit stdin, and no browser launch, returning a versioned JSON envelope. Existing JSON queries reuse raw results; creation, edits, comments, and account/URL queries provide structured data, while other commands currently retain unstable text messages. Human diagnostics stay out of the protocol. `JiraRequestException` retains sanitized HTTP status, retry advice, and unknown write outcomes without adding automatic retries or cross-request transactions. Exit codes and data contracts live in the usage guide.

This mode is a machine interface, not write authorization or user confirmation. The collaboration workflow obtains confirmation of specifications and concrete changes; the CLI executes writes directly without separate approval. Concurrency checks and unknown-outcome handling are technical protections, not credential isolation.

### Editing, export, and content conversion

`JiraIssueDocument` centralizes YAML frontmatter, body, field mapping, and baseline checks for a single Markdown issue file. Create/edit/template/export/update share this format and no longer accept HOCON issue files. SnakeYAML Engine parses nodes, rejecting duplicate keys, aliases, anchors, merge keys, unknown tags, and excessive input while retaining decimal precision directly. Connection and comment metadata remain HOCON.

CLI flags override files; empty strings or omitted fields preserve values and explicit null clears them. Required fields cannot be cleared, and read-only information such as status is not written automatically. Assignees use Cloud account IDs or DC usernames; Epic and Story Points resolve through instance metadata. Exports normalize common references to stable user identifiers, keys, or names, retain custom structures, and store originalFields, description hashes, mode, API version, and body format in the same YAML. Update requires the complete baseline and reuses edit's diff and editable-operation checks. Editing compares file/current updated and checks again before writing; this non-atomic check cannot exclude other clients' writes.

v3 uses ADF; uploads and exports support a strict Markdown subset and reject nodes that cannot round-trip losslessly; ADF table uploads are not yet supported. `JiraMarkdown` parses Markdown with CommonMark's GFM tables extension, converting tables to Wiki headers and data rows for v2 without preserving column alignment markers. Direct v2 writes convert Markdown to Jira Wiki by default or accept explicitly selected native Wiki. Exports preserve original Wiki and record its format to avoid lossy conversion. Approximate Markdown rendering in view commands is read-only and does not change export/upload rules.

`issue view --format markdown` uses the same serializer for selected YAML properties and read-only description rendering, without querying comments. Field order is retained; Epic and Story Points share on-demand metadata without hardcoded IDs. Assignee outputs an importable identity. View documents can feed create/edit, but have no strict baseline and are not lossless exports; unchanged presentation bodies are never written back. Strict export bodies use hashes and are not reconverted when unchanged. Machine mode returns data.key/data.markdown without changing the default JSON view protocol.

Comments, attachments, worklogs, links, clones, and transitions use their respective APIs without sharing exported-file conflict checks. Multi-request operations provide no cross-request transaction. Complete commands, permission limits, pagination, and format boundaries live in the module usage guide.

Create/edit read complete YAML metadata plus description through `--template`, with `--file` as a compatibility alias; template stdin requires the same structure. `--desc` overrides description only, retaining metadata; `--desc -` cannot accompany `--template -`. `DescriptionFormat` provides `--desc-format` for create/edit/update: explicit format wins, explicit description defaults to Markdown, otherwise the recorded template format applies; Wiki requires API v2. Legacy `--body` and `--body-format` remain aliases, and YAML bodyFormat is unchanged; comments and worklogs retain a separate `BodyFormat`. The fixed template container needs no format option, so `TemplateFormat`, `--template-format`, and filename-based format guessing are removed. Epic creation reuses the loaded template to avoid duplicate stdin reads; comment template semantics are unchanged.

Issue lists display an `EPIC` key column by default, reusing table, TSV, and CSV rendering. Data Center discovers Epic Link through field metadata; Cloud reads `parent` with Epic issue-type information, without per-issue parent requests or treating ordinary parent tasks as Epics. Related fields are requested only when Epic information is needed; raw JSON gains no synthetic fields.
