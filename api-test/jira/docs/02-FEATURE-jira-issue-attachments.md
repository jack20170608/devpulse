# Jira Cloud 工单附件管理 / Jira Cloud issue attachment management

[中文](#中文) | [English](#english)

## 中文

### 功能与接口

请求使用 JetBrains HTTP Client 和 Jira Cloud REST API v2。请单独执行请求，不要使用“运行全部请求”：上传会新增附件，删除不可恢复。

| 文件 | 请求名称 | 方法与路径 | 成功状态 |
| --- | --- | --- | --- |
| [上传](../04-attachment-management/0401_upload.http) | `jiraUploadIssueAttachment` | `POST /rest/api/2/issue/{issueKey}/attachments` | `200` |
| [查询与下载](../04-attachment-management/0402_query_download.http) | `jiraAttachmentSettings` | `GET /rest/api/2/attachment/meta` | `200` |
| 查询与下载 | `jiraListIssueAttachments` | `GET /rest/api/2/issue/{issueKey}?fields=attachment` | `200` |
| 查询与下载 | `jiraAttachmentMetadata` | `GET /rest/api/2/attachment/{attachmentId}` | `200` |
| 查询与下载 | `jiraDownloadAttachment` | `GET /rest/api/2/attachment/content/{attachmentId}?redirect=false` | `200` |
| [删除与核验](../04-attachment-management/0403_delete.http) | `jiraDeleteAttachment` | `DELETE /rest/api/2/attachment/{attachmentId}` | `204`，响应体为空 |
| 删除与核验 | `jiraVerifyAttachmentDeleted` | `GET /rest/api/2/attachment/{attachmentId}` | `404` |

附件列表位于工单响应的 `fields.attachment` 数组中；空数组表示没有附件。元数据包含 ID、文件名、大小、作者和下载地址。

### 环境配置

选择测试环境，先执行[认证请求](../01-verify/01_auth-and-server.http)中的 `jiraMyself`，初始化当前 IDE 会话的 `JIRA_BASIC`。重启 IDE 或切换账号、环境后重新认证。

当前附件请求使用大写环境变量。将以下参数合并到本地 `http-client.private.env.json` 的同名环境中，不要覆盖已有认证配置：

```json
{
  "dev": {
    "JIRA_BASE_URL": "https://your-domain.atlassian.net",
    "ISSUE_KEY": "TEST-123",
    "ALLOW_WRITES": false,
    "ATTACHMENT_ID_1": "",
    "CONFIRM_ATTACHMENT_DELETE_ID": ""
  }
}
```

| 变量 | 用途 |
| --- | --- |
| `JIRA_BASE_URL` | 可信 HTTPS API 基址，不带末尾斜杠 |
| `ISSUE_KEY` | 人工确认允许操作的测试工单 Key |
| `ALLOW_WRITES` | 仅布尔值 `true` 或精确字符串 `"true"` 允许上传和删除 |
| `ATTACHMENT_ID_1` | 从目标工单列表人工选择的数字附件 ID，建议保存为字符串；查询、下载、删除使用同一变量 |
| `CONFIRM_ATTACHMENT_DELETE_ID` | 必须与 `ATTACHMENT_ID_1` 的字符串值完全一致；平时保持空字符串 |

删除不会使用 `ATTACHMENT_ID_2`，上传脚本也不会自动选择删除目标。

### 上传与下载

上传路径和显示名称直接写在 `0401_upload.http` 的 multipart 部分中，不使用环境变量。每个文件的表单字段名均为 `file`，`filename` 为 Jira 显示名称，`<` 后的路径为实际读取的本地文件；不会重命名本地文件。

当前文件来自 `files\build.log` 和 `files\small-dog.png`。修改 multipart 部分时，同步维护响应脚本的 `expectedFilenames`，确保其与每个实际 `filename` 一致且唯一。断言检查数量和文件名，不依赖返回顺序。只上传不含敏感信息的测试文件；上传部分失败或超时后先查询列表，不要直接重发。

`jiraAttachmentMetadata` 查询 `ATTACHMENT_ID_1`，每次查询前清除 `attachmentFilename` 和 `attachmentFilenameId` 会话全局变量，成功后保存文件名及对应 ID。切换附件或环境后重新查询。

下载请求使用 `redirect=false`，通过 `>> ./download/{{ISSUE_KEY}}-{{attachmentFilename}}` 保存。使用前人工确认 `attachmentFilenameId` 与当前 ID 一致，文件名符合本地文件系统规则且不含路径分隔符。同名文件存在时，`>>` 生成带后缀的新文件，不覆盖旧文件。失败响应也可能被保存，必须先确认状态断言成功。

### 删除执行顺序与断言

1. 保持 `ALLOW_WRITES: false`，认证并确认账号，执行 `jiraListIssueAttachments`。
2. 人工选择可丢弃的测试附件，将 ID 填入 `ATTACHMENT_ID_1`；通过列表和 `jiraAttachmentMetadata` 确认所属工单、文件名、大小、作者，必要时先下载备份。
3. 将 `ALLOW_WRITES` 设置为 `true`，将 `CONFIRM_ATTACHMENT_DELETE_ID` 设置为相同 ID，单独执行 `jiraDeleteAttachment`。
4. 前置脚本在写入未启用、ID 缺失或非数字、确认值缺失或不一致时中止，不发送删除请求。响应必须为 `204` 且无响应体，`200`、`401`、`403`、`404`、重定向等均不算删除成功。
5. 仅删除成功后，脚本将基址、工单 Key、附件 ID 保存到会话全局变量 `deletedAttachment`。单独执行 `jiraVerifyAttachmentDeleted`，断言同一附件的元数据接口返回 `404`；没有成功记录或基址、工单、ID 发生变化时，核验请求被阻止。
6. 重新执行 `jiraListIssueAttachments`，确认目标 ID 不再出现在列表中。将 `ALLOW_WRITES` 恢复为 `false`，将确认值清空。核验请求是只读操作，无需保持写入开关开启或确认值非空。

每次尝试删除前都会清除旧成功记录，避免失败后误用旧记录。不要通过重复执行删除请求测试成功：第二次通常返回 `404`，它不是本次删除成功的 `204`。超时不代表失败，应先查询列表和元数据核实结果，不盲目重试。

`404` 也可能表示无权查看，不能单独证明删除成功。核验必须基于此前成功的 `204`，期间不要切换账号或权限，并结合工单列表确认。

### 完整生命周期示例

[`0404_full_lifecycle.http`](../04-attachment-management/0404_full_lifecycle.http) 提供七步闭环：上传 → 工单列表核验 → 元数据核验 → 下载 → 删除 → 元数据 `404` 核验 → 工单列表缺失核验。

先运行 `jiraMyself`，选择测试环境并设置 `ALLOW_WRITES: true`，然后按文件中的 1–7 顺序执行。上传使用 `files\build.log`，显示名称为 `lifecycle-build.log`，下载仅在响应视图查看，不另存本地文件。运行示例即表示允许删除其新建的测试附件；不需要填写 `ATTACHMENT_ID_1` 或 `CONFIRM_ATTACHMENT_DELETE_ID`，也不会删除已有附件。

示例使用独立会话变量 `jiraAttachmentLifecycle` 保存本次上传返回的 ID、文件名、大小、基址、工单和执行阶段；后续附件 ID 通过请求局部变量传递。每一步校验前置阶段及原始环境，响应断言失败不会推进阶段。删除前将阶段标为 `deletePending`，避免超时后直接重复删除。不要并行运行多个生命周期，也不要中途切换账号或权限。

最终列表核验通过后自动清除会话状态，随后将 `ALLOW_WRITES` 恢复为 `false`。中途失败时不会自动清理：只读步骤可以在排查后重试；上传或删除结果不明时，先人工查询工单列表和元数据。可在 HTTP Client 脚本中通过 `client.global.get("jiraAttachmentLifecycle")` 查看记录；确认遗留附件并按需使用 `0403_delete.http` 清理后，才通过 `client.global.clear("jiraAttachmentLifecycle")` 清除记录并重新开始。不要仅凭文件名删除附件；上传响应未捕获 ID 时需结合作者、时间和大小核实。

### 安全与兼容性

附件 ID 是全局标识，删除接口不带工单 Key。确认值与写入开关只防止误操作，不验证附件归属，也不替代服务端授权；删除前必须从指定工单列表核实目标。

读取需要工单可见权限；上传需要创建附件权限；删除需要删除自己或所有附件的权限，并受到工单安全级别和 API Token 授权范围限制。`401`、`403`、`404`、`413`、`429` 应按实际响应排查认证、权限、目标、大小限制和限流。

删除和核验请求均设置 `@no-redirect`、`@no-log`。不要开启自动重定向来绕过认证问题，或向未经核实的响应链接转发认证头。`@no-log` 不保证服务端、代理或 IDE 响应视图不记录数据。不要提交附件、凭据或本地环境文件。

上传需要 `X-Atlassian-Token: no-check`，这不替代认证或权限。上传前通过 `jiraAttachmentSettings` 检查 `enabled` 和 `uploadLimit`。下载接口面向 Jira Cloud，不保证兼容 Server / Data Center。Scoped API Token 或 OAuth 网关基址及认证方式需按凭据类型核对。

### 官方参考

- [Jira Cloud REST API v2 附件接口](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-attachments/)
- [JetBrains HTTP Client 请求语法](https://www.jetbrains.com/help/idea/exploring-http-syntax.html)

## English

### Features and endpoints

These requests use JetBrains HTTP Client and Jira Cloud REST API v2. Run requests individually, not with “Run All”: uploads create attachments, and deletion is irreversible.

| File | Request name | Method and path | Success status |
| --- | --- | --- | --- |
| [Upload](../04-attachment-management/0401_upload.http) | `jiraUploadIssueAttachment` | `POST /rest/api/2/issue/{issueKey}/attachments` | `200` |
| [Query and download](../04-attachment-management/0402_query_download.http) | `jiraAttachmentSettings` | `GET /rest/api/2/attachment/meta` | `200` |
| Query and download | `jiraListIssueAttachments` | `GET /rest/api/2/issue/{issueKey}?fields=attachment` | `200` |
| Query and download | `jiraAttachmentMetadata` | `GET /rest/api/2/attachment/{attachmentId}` | `200` |
| Query and download | `jiraDownloadAttachment` | `GET /rest/api/2/attachment/content/{attachmentId}?redirect=false` | `200` |
| [Delete and verify](../04-attachment-management/0403_delete.http) | `jiraDeleteAttachment` | `DELETE /rest/api/2/attachment/{attachmentId}` | `204`, empty response body |
| Delete and verify | `jiraVerifyAttachmentDeleted` | `GET /rest/api/2/attachment/{attachmentId}` | `404` |

Attachments appear in the issue response's `fields.attachment` array; an empty array means no attachments. Metadata includes ID, filename, size, author, and download URL.

### Environment configuration

Select the test environment and first run `jiraMyself` in the [authentication requests](../01-verify/01_auth-and-server.http) to initialize `JIRA_BASIC` in the current IDE session. Authenticate again after restarting the IDE or changing accounts or environments.

Current attachment requests use uppercase environment variables. Merge these parameters into the matching environment in local `http-client.private.env.json`, preserving existing authentication settings:

```json
{
  "dev": {
    "JIRA_BASE_URL": "https://your-domain.atlassian.net",
    "ISSUE_KEY": "TEST-123",
    "ALLOW_WRITES": false,
    "ATTACHMENT_ID_1": "",
    "CONFIRM_ATTACHMENT_DELETE_ID": ""
  }
}
```

| Variable | Purpose |
| --- | --- |
| `JIRA_BASE_URL` | Trusted HTTPS API base URL without a trailing slash |
| `ISSUE_KEY` | Manually confirmed test issue key |
| `ALLOW_WRITES` | Only boolean `true` or the exact string `"true"` enables uploads and deletion |
| `ATTACHMENT_ID_1` | Numeric attachment ID manually selected from the target issue, preferably a string; shared by query, download, and deletion |
| `CONFIRM_ATTACHMENT_DELETE_ID` | Must exactly match the string value of `ATTACHMENT_ID_1`; leave empty normally |

Deletion does not use `ATTACHMENT_ID_2`, and uploads never automatically select a deletion target.

### Upload and download

Upload paths and display names are specified directly in multipart sections in `0401_upload.http`, not environment variables. Every file uses the `file` form field; `filename` specifies the Jira display name, and the path after `<` identifies the local file to read. The local file is not renamed.

Current files come from `files\build.log` and `files\small-dog.png`. When editing multipart sections, update the response script's `expectedFilenames` to match every actual `filename` uniquely. Assertions check count and filenames without depending on response order. Upload only nonsensitive test files; inspect the list before retrying a partial or timed-out upload.

`jiraAttachmentMetadata` queries `ATTACHMENT_ID_1`, clearing the session globals `attachmentFilename` and `attachmentFilenameId` before each query and saving the filename and corresponding ID on success. Query again after changing attachments or environments.

The download request uses `redirect=false` and saves through `>> ./download/{{ISSUE_KEY}}-{{attachmentFilename}}`. First manually verify that `attachmentFilenameId` matches the current ID and that the filename is valid for the local filesystem without path separators. When a file exists, `>>` creates a suffixed filename rather than overwriting it. Failed responses may also be saved; confirm the status assertion passes before using a downloaded file.

### Deletion sequence and assertions

1. Keep `ALLOW_WRITES: false`, authenticate and confirm the account, then run `jiraListIssueAttachments`.
2. Manually select a disposable test attachment and set `ATTACHMENT_ID_1`; confirm its issue, filename, size, and author using the list and `jiraAttachmentMetadata`. Download a backup first if needed.
3. Set `ALLOW_WRITES` to `true` and `CONFIRM_ATTACHMENT_DELETE_ID` to the same ID, then run `jiraDeleteAttachment` individually.
4. The pre-request script aborts without sending DELETE if writes are disabled, the ID is missing or nonnumeric, or confirmation is missing or mismatched. The response must be `204` with no body; `200`, `401`, `403`, `404`, and redirects are not successful deletions.
5. Only after successful deletion, the script saves the base URL, issue key, and attachment ID in session global `deletedAttachment`. Run `jiraVerifyAttachmentDeleted` individually to assert that the same attachment's metadata endpoint returns `404`. Verification is blocked without a success record or after changing the base URL, issue, or ID.
6. Run `jiraListIssueAttachments` again and confirm that the target ID is absent. Restore `ALLOW_WRITES` to `false` and clear confirmation. Verification is read-only and does not require writes enabled or a nonempty confirmation value.

Every deletion attempt clears the previous success record to prevent reuse after failure. Do not test success by repeating DELETE: a second call typically returns `404`, not the `204` required for a successful deletion. A timeout does not mean failure; inspect the list and metadata before retrying.

A `404` can also mean lack of visibility and cannot independently prove deletion. Verification must follow a successful `204`; do not change accounts or permissions between requests, and also confirm against the issue list.

### Full lifecycle example

[`0404_full_lifecycle.http`](../04-attachment-management/0404_full_lifecycle.http) provides seven steps: upload → issue-list verification → metadata verification → download → delete → metadata `404` verification → issue-list absence verification.

First run `jiraMyself`, select the test environment, and set `ALLOW_WRITES: true`, then execute steps 1–7 in file order. The upload uses `files\build.log` with display name `lifecycle-build.log`; downloaded content is viewed in the response pane without saving a local file. Running this example authorizes deletion of its newly created test attachment. It does not require `ATTACHMENT_ID_1` or `CONFIRM_ATTACHMENT_DELETE_ID` and does not delete existing attachments.

The independent session variable `jiraAttachmentLifecycle` stores the returned upload ID, filename, size, base URL, issue, and execution phase; subsequent attachment IDs are passed through request-local variables. Every step checks its prerequisite phase and original environment; failed response assertions do not advance the phase. Deletion first marks the phase `deletePending` to prevent directly repeating a timed-out DELETE. Do not run lifecycles concurrently or change accounts or permissions mid-run.

Successful final list verification automatically clears session state; then restore `ALLOW_WRITES` to `false`. Failures do not trigger automatic cleanup: retry read-only steps after investigating; inspect the issue list and metadata first if upload or deletion results are uncertain. Inspect the record in an HTTP Client script with `client.global.get("jiraAttachmentLifecycle")`. Only after identifying leftover attachments and cleaning up with `0403_delete.http` if necessary, use `client.global.clear("jiraAttachmentLifecycle")` to reset and restart. Never delete by filename alone; if the upload ID was not captured, also verify author, time, and size.

### Safety and compatibility

Attachment IDs are global, and DELETE does not include an issue key. Confirmation and the write switch prevent accidental operations but do not validate ownership or replace server authorization. Verify the target against the selected issue's list before deletion.

Reading requires issue visibility; uploading requires create-attachment permission; deletion requires permission to delete one's own or all attachments, subject to issue security and API token scopes. Investigate `401`, `403`, `404`, `413`, and `429` for authentication, permissions, target selection, size limits, and rate limiting as appropriate.

Deletion and verification set `@no-redirect` and `@no-log`. Do not enable automatic redirects to bypass authentication problems or forward authentication headers to unverified response links. `@no-log` does not prevent server, proxy, or IDE response-view logging. Do not commit attachments, credentials, or local environment files.

Uploads require `X-Atlassian-Token: no-check`, which does not replace authentication or permissions. Check `enabled` and `uploadLimit` with `jiraAttachmentSettings` before uploading. The download endpoint targets Jira Cloud and is not guaranteed compatible with Server / Data Center. Verify gateway URLs and authentication against the scoped API token or OAuth credential type.

### Official references

- [Jira Cloud REST API v2 attachment endpoints](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-attachments/)
- [JetBrains HTTP Client request syntax](https://www.jetbrains.com/help/idea/exploring-http-syntax.html)
