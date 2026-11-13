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

`src/main/resources/skills/jira-requirements` 随 Jira JAR 打包，包含简短 Skill、执行说明及规格书/Jira 描述模板。`SKILL`、`references/workflow` 和 `templates/jira-description` 各分为 `.en.md` 和 `.zh.md`，规格书模板仍中英对照维护；`JiraSkillCommands` 通过离线 `skill export --language en|zh` 选择对应语言文件，规格书模板提取单一语言正文，默认英文，将完整资源包写入新目录。导出统一使用 `SKILL.md`、`references/workflow.md` 和 `templates/jira-description.md` 等标准文件名。优先使用显式模板目录或当前工作目录 `.jira/templates` 的同名覆盖文件，缺失项回退所选语言的内置资源；自定义模板原样复制，不自动翻译。不修改已有安装、不调用 Jira 或 LLM，也不依赖连接配置或 token。

该 Skill 将分析和内容生成交给 AI：新需求规格及具体创建内容确认后直接创建，已有 Issue 严格导出基线，规格及差异确认后直接同步，并核验结果。覆盖文件只是格式输入，不是执行指令。此协作流程复用既有 create/export/edit 与机器协议，不增加授权状态机或独立终端审批，不替代工具权限；详细模板优先级、确认和故障处理维护在使用说明及资源包中。

### 机器调用协议

`JiraMachine` 作为 picocli 执行 Adapter，提供显式 `--machine`，不新建工具模块或复制业务操作。该模式强制无交互、显式 stdin 和不启动浏览器，通过版本化 JSON 信封返回结果；已有 JSON 查询复用原始结果，创建、编辑、评论及账户/链接查询提供结构化数据，其他命令暂保留非稳定的文本消息。人类诊断不混入协议。`JiraRequestException` 保留脱敏的 HTTP 状态、重试提示和写操作结果不确定性；不添加自动重试，不提供跨请求事务。具体退出码与数据契约见模块使用说明。

该模式只是机器接口，不代表写权限或用户确认。用户对规格及具体变更的确认由协作流程负责，CLI 直接执行写命令，不增加独立审批；并发检查与未知结果处理属于技术保护，不能替代凭据隔离。

### 编辑、导出与内容转换

直接创建及编辑按 Jira 实例元数据解析字段；导出保留 `/editmeta` 返回的可编辑字段及原始结构，并保存字段基线、描述哈希、API 版本和正文格式。更新只发送已修改字段，上传前校验 `updated`；这是非原子的冲突检查，不能承诺与其他客户端写入互斥。

v3 使用 ADF，上传和导出只支持严格的 Markdown 子集，不能无损表示的节点拒绝往返；暂不支持 ADF 表格上传。`JiraMarkdown` 使用 CommonMark 的 GFM 表格扩展解析 Markdown，v2 将表格转换为 Wiki 表头和数据行，不保留列对齐标记。v2 直接写入默认将 Markdown 转为 Jira Wiki，也可显式提交原生 Wiki；导出保留原始 Wiki，并记录格式，避免有损转换。查看命令的近似 Markdown 渲染只读，不改变导出或上传规则。

`issue view --format markdown` 将所选属性输出为 YAML frontmatter，正文复用只读 description 渲染，不查询评论。字段选择保留顺序；Epic 和 Story Points 按需共用一次字段元数据查询，不硬编码自定义字段 ID。此文档不包含更新基线；机器模式通过 `data.key` 和 `data.markdown` 返回，不改变原有默认 JSON 查看协议。

评论、附件、工时、关联、克隆及状态流转由各自 API 执行，不共享导出文件的冲突检查；多请求操作不提供跨请求事务。完整命令、权限限制、分页与格式边界统一维护在模块使用说明中。

创建和直接编辑命令共用 `TemplateFormat` mixin，`--template-format` 默认 `auto`，仅根据 `--template` 文件的后缀选择输入格式：`.md` / `.markdown` 不区分大小写，其他后缀及标准输入按 Wiki 处理；不检查内容猜测格式。显式模板格式或兼容的 `--body-format` 可覆盖自动判断；Wiki 仍要求 API v2。`issue edit --file` 仍使用导出元数据中的格式，其他描述来源和其他命令的默认格式不变。

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

`src/main/resources/skills/jira-requirements` ships inside the Jira JAR, containing a concise skill, execution guide, and specification/Jira description templates. `SKILL`, `references/workflow`, and `templates/jira-description` each have separate `.en.md` and `.zh.md` sources; the specification template remains bilingual. Through offline `skill export --language en|zh`, `JiraSkillCommands` selects matching language files, extracts a single language body for the specification template, and writes the complete bundle into a new directory, defaulting to English. Exports retain standard filenames such as `SKILL.md`, `references/workflow.md`, and `templates/jira-description.md`. Same-named overrides from an explicit template directory or the working directory's `.jira/templates` take precedence, with missing files falling back to the selected language's bundled resources. Custom templates are copied without translation. It modifies no existing installation, calls neither Jira nor an LLM, and requires no connection configuration or token.

The skill delegates analysis and content generation to AI: new requirements are created directly after the specification and concrete creation content are confirmed; existing issues retain strict export baselines and synchronize after specification/diff confirmation, followed by verification. Overrides provide formatting, not executable instructions. This collaboration workflow reuses existing create/export/edit operations and the machine protocol without adding an authorization state machine or separate terminal approval, or replacing tool permissions. Template precedence, confirmation, and failure handling live in the usage guide and bundled resources.

### Machine invocation protocol

`JiraMachine` is a picocli execution Adapter offering explicit `--machine` without adding a tool module or duplicating business operations. It forces noninteractive execution, explicit stdin, and no browser launch, returning a versioned JSON envelope. Existing JSON queries reuse raw results; creation, edits, comments, and account/URL queries provide structured data, while other commands currently retain unstable text messages. Human diagnostics stay out of the protocol. `JiraRequestException` retains sanitized HTTP status, retry advice, and unknown write outcomes without adding automatic retries or cross-request transactions. Exit codes and data contracts live in the usage guide.

This mode is a machine interface, not write authorization or user confirmation. The collaboration workflow obtains confirmation of specifications and concrete changes; the CLI executes writes directly without separate approval. Concurrency checks and unknown-outcome handling are technical protections, not credential isolation.

### Editing, export, and content conversion

Direct creation and editing resolve fields against Jira instance metadata. Exports retain editable `/editmeta` fields and their original structure, storing field baselines, description hashes, API version, and body format. Updates send only modified fields and check `updated` before uploading; this non-atomic conflict check cannot guarantee exclusion of other clients' writes.

v3 uses ADF; uploads and exports support a strict Markdown subset and reject nodes that cannot round-trip losslessly; ADF table uploads are not yet supported. `JiraMarkdown` parses Markdown with CommonMark's GFM tables extension, converting tables to Wiki headers and data rows for v2 without preserving column alignment markers. Direct v2 writes convert Markdown to Jira Wiki by default or accept explicitly selected native Wiki. Exports preserve original Wiki and record its format to avoid lossy conversion. Approximate Markdown rendering in view commands is read-only and does not change export/upload rules.

`issue view --format markdown` renders selected properties as YAML frontmatter and reuses read-only description rendering for the body, without querying comments. Selection preserves field order; Epic and Story Points share one on-demand metadata request without hardcoded custom field IDs. This document has no update baseline. Machine mode returns `data.key` and `data.markdown` without changing the existing default JSON view protocol.

Comments, attachments, worklogs, links, clones, and transitions use their respective APIs without sharing exported-file conflict checks. Multi-request operations provide no cross-request transaction. Complete commands, permission limits, pagination, and format boundaries live in the module usage guide.

Creation and direct editing share a `TemplateFormat` mixin that defaults `--template-format` to `auto`, selecting the input format only from the `--template` file suffix: `.md` / `.markdown` match case-insensitively; other suffixes and stdin use Wiki, without content guessing. Explicit template format or the compatible `--body-format` option overrides detection; Wiki still requires API v2. `issue edit --file` still uses the format in export metadata. Defaults for other description sources and other commands are unchanged.

Issue lists display an `EPIC` key column by default, reusing table, TSV, and CSV rendering. Data Center discovers Epic Link through field metadata; Cloud reads `parent` with Epic issue-type information, without per-issue parent requests or treating ordinary parent tasks as Epics. Related fields are requested only when Epic information is needed; raw JSON gains no synthetic fields.
