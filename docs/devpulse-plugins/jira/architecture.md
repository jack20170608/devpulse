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

### 编辑、导出与内容转换

直接创建及编辑按 Jira 实例元数据解析字段；导出保留 `/editmeta` 返回的可编辑字段及原始结构，并保存字段基线、描述哈希、API 版本和正文格式。更新只发送已修改字段，上传前校验 `updated`；这是非原子的冲突检查，不能承诺与其他客户端写入互斥。

v3 使用 ADF，上传和导出只支持严格的 Markdown 子集，不能无损表示的节点拒绝往返；暂不支持 ADF 表格上传。`JiraMarkdown` 使用 CommonMark 的 GFM 表格扩展解析 Markdown，v2 将表格转换为 Wiki 表头和数据行，不保留列对齐标记。v2 直接写入默认将 Markdown 转为 Jira Wiki，也可显式提交原生 Wiki；导出保留原始 Wiki，并记录格式，避免有损转换。查看命令的近似 Markdown 渲染只读，不改变导出或上传规则。

评论、附件、工时、关联、克隆及状态流转由各自 API 执行，不共享导出文件的冲突检查；多请求操作不提供跨请求事务。完整命令、权限限制、分页与格式边界统一维护在模块使用说明中。

创建命令的 `--template-format` 默认 `auto`，仅根据 `--template` 文件的后缀选择输入格式：`.md` / `.markdown` 不区分大小写，其他后缀及标准输入按 Wiki 处理；不检查内容猜测格式。显式模板格式或兼容的 `--body-format` 可覆盖自动判断；Wiki 仍要求 API v2。其他描述来源和其他命令的默认格式不变。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugins/jira/README.md)

### Module and layers

Jira is a standalone executable that depends on neither core, Pulse, nor Gather and needs no Bash to run its JAR. `JiraCommand` defines the picocli command tree and coordinates connections, input, and output. Command classes in `cli` divide creation, editing, querying, assignment, transitions, other issue operations, and Agile/catalog commands.

`JiraConfiguration` discovers and validates HOCON connection configuration. `JiraClient` owns Java HTTP requests, authentication, REST paths, field metadata, and pagination. `JiraMarkdown` handles uploads and strict round-trip conversion; `JiraDescriptionMarkdown` handles approximate read-only Markdown presentation. Presentation fallback rules must not be applied to lossless exports.

### Configuration and protocol boundaries

Connection files are selected as a whole in priority order: explicit `--config`, current working directory, then user home. Files are not merged. Only the token comes from `JIRA_API_TOKEN` and is never written to configuration or issue templates. Configuration supports no includes or system-property/environment substitution.

Deployment mode determines authentication, user identities, and search pagination: Cloud uses email/token Basic authentication, `accountId`, and `nextPageToken`; Data Center uses PAT Bearer authentication, usernames, and `startAt`. API version separately determines REST paths and body format, defaulting to Cloud v3 or Data Center v2 with an explicit override. Availability of the selected API version is not guaranteed.

The HTTP client follows no redirects and sets connection and request timeouts. Remote plaintext HTTP is refused; only local HTTP testing is allowed. Failures display HTTP status codes rather than Jira error bodies or authentication information.

### Editing, export, and content conversion

Direct creation and editing resolve fields against Jira instance metadata. Exports retain editable `/editmeta` fields and their original structure, storing field baselines, description hashes, API version, and body format. Updates send only modified fields and check `updated` before uploading; this non-atomic conflict check cannot guarantee exclusion of other clients' writes.

v3 uses ADF; uploads and exports support a strict Markdown subset and reject nodes that cannot round-trip losslessly; ADF table uploads are not yet supported. `JiraMarkdown` parses Markdown with CommonMark's GFM tables extension, converting tables to Wiki headers and data rows for v2 without preserving column alignment markers. Direct v2 writes convert Markdown to Jira Wiki by default or accept explicitly selected native Wiki. Exports preserve original Wiki and record its format to avoid lossy conversion. Approximate Markdown rendering in view commands is read-only and does not change export/upload rules.

Comments, attachments, worklogs, links, clones, and transitions use their respective APIs without sharing exported-file conflict checks. Multi-request operations provide no cross-request transaction. Complete commands, permission limits, pagination, and format boundaries live in the module usage guide.

The create command defaults `--template-format` to `auto`, selecting the input format only from the `--template` file suffix: `.md` / `.markdown` match case-insensitively; other suffixes and stdin use Wiki, without content guessing. Explicit template format or the compatible `--body-format` option overrides detection; Wiki still requires API v2. Defaults for other description sources and other commands are unchanged.
