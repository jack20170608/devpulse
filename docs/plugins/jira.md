# Jira 插件指南 / Jira plugin guide

[中文](#中文) | [English](#english)

## 中文

[项目首页](../../readme.MD)

`devpulse-jira` 是 `devpulse-plugins` Maven 聚合模块下独立可运行的 `jira` 子模块与 Jira REST 客户端，支持 Jira Cloud（REST v3）和 Data Center（REST v2）。核心 `devpulse` 不加载或依赖它，也不需要 Bash 来执行 Jira JAR。根目录 `mvn clean package` 生成 `devpulse-plugins/jira/target/devpulse-jira.jar`；`bash run-jira.sh` 仅构建该模块并启动。**未连接真实 Jira 实例验证**；先在测试项目中检查字段格式、权限与工作流。

### 认证

仅从环境变量读取连接信息，不在模板中保存凭据：

```bash
export DEVPULSE_JIRA_MODE=cloud                 # Data Center 使用 dc
export DEVPULSE_JIRA_URL=https://example.atlassian.net
export DEVPULSE_JIRA_EMAIL=user@example.com    # 仅 Cloud
export DEVPULSE_JIRA_TOKEN='your-api-token'     # Cloud API Token / Data Center PAT
```

使用 HTTPS；仅本机测试允许 HTTP。请勿将包含凭据的 shell 历史、环境转储、导出的 Jira 数据或附件提交到版本库。访问失败只显示 HTTP 状态码，不输出 Jira 的错误正文。

### 命令

```bash
bash run-jira.sh init --file issue.conf
# 编辑 issue.conf 中的 Jira REST fields 和相邻的 issue.md
bash run-jira.sh create --file issue.conf
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

`init` 和 `export` 只创建新文件，不覆盖已有文件。创建时 `fields` 必须包含 `project`、`issuetype`、`summary`；描述取同名 `.md`。导出仅保留 Jira `/editmeta` 返回的可编辑字段及描述，其他字段不会被回写；自定义字段保持原始 JSON/HOCON 结构，需按该 Jira 实例的 REST 字段格式修改。`update` 比较导出时的字段，只发送修改过的字段；修改描述时将 Markdown 转成 Jira 内容格式。更新成功后重新导出再编辑。

`update` 和 `comment-update` 上传前比较导出时和当前 Jira 的 `updated` 值，发现变化默认拒绝；`--force` 显式跳过检查。评论导出创建 `.md` 与同名 `.conf` 元数据，成功更新后需要重新导出。**这是上传前检查，不是原子并发锁**：检查与更新之间若发生并发写入仍可能冲突。状态更改使用 `transitions` 查到的**转换 ID**，不是状态名；新评论、状态转换和附件不使用导出文件的并发检查。

### Markdown 边界

Cloud 描述、评论支持普通段落、1–6 级标题、简单列表、无语言标注代码块，以及文本、加粗、斜体、行内代码、HTTP(S) 链接。复杂或未知 ADF 节点、富文本标记、嵌套列表及无法无损表示的内容会拒绝导出；编辑后的不支持语法会拒绝上传，而不是静默丢弃。Data Center 使用 Jira wiki 格式而非 ADF，当前**只支持纯文本描述和评论**，遇到可识别的 wiki/Markdown 格式会拒绝转换；不能保证识别所有插件的私有格式。其他可编辑字段保留为原始结构，不将插件字段强行转换成 Markdown。附件大小限制为 25 MB。

## English

[Project home](../../readme.MD)

`devpulse-jira` is an independently executable `jira` child module under the `devpulse-plugins` Maven aggregator and a Jira REST client for Jira Cloud (REST v3) and Data Center (REST v2). The core `devpulse` neither loads nor depends on it; the Jira JAR itself does not need Bash. From the repository root, `mvn clean package` produces `devpulse-plugins/jira/target/devpulse-jira.jar`; `bash run-jira.sh` builds only that module and launches it. **It has not been validated against a live Jira instance**; first check field shapes, permissions, and workflows in a test project.

### Authentication

Connection settings come only from environment variables; templates never store credentials:

```bash
export DEVPULSE_JIRA_MODE=cloud                 # Use dc for Data Center
export DEVPULSE_JIRA_URL=https://example.atlassian.net
export DEVPULSE_JIRA_EMAIL=user@example.com    # Cloud only
export DEVPULSE_JIRA_TOKEN='your-api-token'     # Cloud API Token / Data Center PAT
```

Use HTTPS; HTTP is allowed only for local testing. Do not commit shell history containing credentials, environment dumps, exported Jira data, or attachments. HTTP failures display only the status code, not the Jira response body.

### Commands

```bash
bash run-jira.sh init --file issue.conf
# Edit Jira REST fields in issue.conf and the adjacent issue.md
bash run-jira.sh create --file issue.conf
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

`init` and `export` create new files only; neither overwrites existing files. For creation, `fields` must contain `project`, `issuetype`, and `summary`; the description comes from the adjacent `.md`. Export includes only fields reported editable by Jira `/editmeta`, plus the description; other fields are never uploaded. Custom fields retain their original JSON/HOCON structure and must follow the REST field format of the particular Jira instance. `update` compares fields with the exported baseline and sends only changes; edited descriptions are converted from Markdown to Jira content. Export again after a successful update.

Before upload, `update` and `comment-update` compare Jira's current `updated` value with its value at export, refusing stale changes by default; `--force` explicitly bypasses this check. Comment export creates a `.md` file and adjacent `.conf` metadata; re-export after a successful update. **This preflight check is not an atomic concurrency lock**: a write between checking and updating can still race. Change status by the **transition ID** returned by `transitions`, not a status name. New comments, transitions, and attachments do not use the export file's concurrency check.

### Markdown limits

Cloud descriptions and comments support paragraphs, headings (levels 1–6), simple lists, unlabelled code fences, text, bold, italic, inline code, and HTTP(S) links. Export refuses complex or unknown ADF nodes/marks, nested lists, and content that cannot be represented losslessly; unsupported edited syntax is rejected rather than silently dropped. Data Center uses Jira wiki syntax rather than ADF; it currently supports **plain-text descriptions and comments only**, rejecting recognizable wiki/Markdown syntax. Proprietary plugin syntax cannot always be identified. Other editable fields retain their raw structure instead of forcing plugin fields into Markdown. Attachments are limited to 25 MB.
