# Jira Cloud 工单附件管理

## 功能与接口

[附件管理请求文件](../04-attachment-management/0401_upload.http) 使用 JetBrains HTTP Client 和 Jira Cloud REST API v2，提供六个独立请求。不要使用“运行全部请求”，上传会新增附件，删除不可恢复。

| 请求名称 | 方法与路径 | 成功状态 |
| --- | --- | --- |
| `jiraAttachmentSettings` | `GET /rest/api/2/attachment/meta` | `200` |
| `jiraListIssueAttachments` | `GET /rest/api/2/issue/{issueKey}?fields=attachment` | `200` |
| `jiraAttachmentMetadata` | `GET /rest/api/2/attachment/{attachmentId}` | `200` |
| `jiraUploadIssueAttachment` | `POST /rest/api/2/issue/{issueKey}/attachments` | `200` |
| `jiraDownloadAttachment` | `GET /rest/api/2/attachment/content/{attachmentId}?redirect=false` | `200` |
| `jiraDeleteAttachment` | `DELETE /rest/api/2/attachment/{attachmentId}` | `204` |

附件列表位于工单响应的 `fields.attachment` 数组中；空数组表示当前没有附件。元数据包含附件 ID、文件名、大小、作者和下载地址等信息。

`0402_query.http` 中的 `jiraAttachmentMetadata` 使用 `ATTACHMENT_ID_1` 查询附件。响应成功且元数据校验通过后，将原始文件名保存到 HTTP Client 会话全局变量 `attachmentFilename`，并将对应附件 ID 保存到 `attachmentFilenameId`。后续请求可通过 `{{attachmentFilename}}` 引用文件名，脚本中可通过 `client.global.get("attachmentFilename")` 读取，不需要增加环境变量。

每次元数据查询前会清除这两个全局变量，避免查询失败后误用旧值。切换附件或环境后应重新查询；将文件名用于本地保存路径前，必须确认 `attachmentFilenameId` 与当前附件 ID 一致，并校验文件名符合本地文件系统规则。这一步只保存变量，不会自动修改下载请求的保存路径。

## 环境配置

在新文件的 HTTP Client 环境选择器中，选择与认证请求相同的测试环境。先执行 [认证请求](../01-verify/01_auth-and-server.http) 中的 `jiraMyself`，初始化当前 IDE 会话的 `JIRA_BASIC`。重启 IDE 或切换账号、环境后，应重新执行认证请求。

本文件沿用当前请求文件的大写环境变量，不使用旧指南中的小写变量名：

| 变量 | 用途 |
| --- | --- |
| `JIRA_BASE_URL` | Jira Cloud API 基址，使用可信 HTTPS 地址，不带末尾斜杠 |
| `ISSUE_KEY` | 人工确认可操作的测试工单 Key |
| `ALLOW_WRITES` | 仅布尔值 `true` 或精确字符串 `"true"` 允许上传和删除；其他值拒绝写入 |
| `ATTACHMENT_ID` | 从目标工单附件列表中人工选择的数字 ID，建议保存为字符串 |
| `CONFIRM_ATTACHMENT_DELETE_ID` | 删除确认值，必须与 `ATTACHMENT_ID` 完全一致；平时保持空字符串 |

将以下参数合并到本地 `http-client.private.env.json` 的同名环境中，不要覆盖已有认证配置：

```json
{
  "jira-v2": {
    "JIRA_BASE_URL": "https://your-domain.atlassian.net",
    "ISSUE_KEY": "TEST-123",
    "ALLOW_WRITES": false,
    "ATTACHMENT_ID": "",
    "CONFIRM_ATTACHMENT_DELETE_ID": ""
  }
}
```

上传文件路径和显示名称直接写在 HTTP 请求中，不使用环境变量。只上传已确认不含敏感信息的测试文件。

## 一次上传多个文件

`jiraUploadIssueAttachment` 当前在同一次请求中上传两个文件：

| 本地路径（相对于 HTTP 文件） | Jira 中的文件名 |
| --- | --- |
| `files\build.log` | `build-main.1.log` |
| `files\small-dog.png` | `small-dog.png` |

每个文件使用独立的 multipart 部分，表单字段名均为 `file`：

```http
--jira-attachment-boundary
Content-Disposition: form-data; name="file"; filename="build-main.1.log"
Content-Type: application/octet-stream

< files\build.log
--jira-attachment-boundary
Content-Disposition: form-data; name="file"; filename="small-dog.png"
Content-Type: image/png

< files\small-dog.png
--jira-attachment-boundary--
```

`filename` 指定上传后的显示名称，不会重命名本地文件；`<` 后面的路径指定实际读取的文件。路径也可以直接使用 Windows 绝对路径，例如 `D:\jira-test\report.pdf`。

添加文件时，在最后的 `--jira-attachment-boundary--` 前增加一个相同结构的部分，修改路径、文件名和内容类型，并同步更新响应脚本中的 `expectedFilenames` 数组。删除文件时同时移除对应部分和预期文件名。每个显示名称应唯一，不包含路径分隔符、双引号或换行。

响应断言检查附件数量，以及每个预期文件名是否恰好对应一个有 ID 的附件，不依赖服务端返回顺序。上传部分失败或超时时，应先查询列表核实已上传的文件，不要直接重发整个批次。

## 推荐执行顺序

1. 保持 `ALLOW_WRITES: false`，先认证并确认账号。
2. 执行 `jiraAttachmentSettings`，检查 `enabled` 和以字节为单位的 `uploadLimit`。附件功能关闭或文件超出限制时，不要上传。
3. 执行 `jiraListIssueAttachments`，确认目标工单和现有附件。
4. 若要上传，逐个检查 multipart 部分中的本地文件、显示文件名，以及响应脚本中的预期文件名列表，然后将 `ALLOW_WRITES` 设置为 `true`，单独执行一次 `jiraUploadIssueAttachment`。
5. 上传成功后重新查询列表，人工选择附件 ID 填入 `ATTACHMENT_ID`。上传脚本不会自动选择删除目标。
6. 执行元数据查询和下载请求。下载文件保存在 HTTP Client 历史目录中，文件名为 `jira-attachment-{ID}.bin`；同名文件存在时，`>>` 会生成带后缀的新文件，不覆盖旧文件。
7. 如需删除，再次确认目标 ID 属于测试工单，且文件名、大小和作者符合预期。将 `CONFIRM_ATTACHMENT_DELETE_ID` 设置为相同 ID，单独执行删除请求。
8. 重新查询列表确认删除结果，并将 `ALLOW_WRITES` 恢复为 `false`、删除确认值恢复为空字符串。

## 安全与兼容性

上传使用 `multipart/form-data`，表单字段名必须是 `file`，并设置 Jira 要求的 `X-Atlassian-Token: no-check`。这个头用于附件上传接口的 CSRF 检查处理，不替代认证或项目权限。

所有请求均禁止自动重定向并禁用请求历史日志。下载通过 `redirect=false` 请求直接内容，避免自动向其他地址转发认证头。不要为了解决下载失败而启用自动重定向，或把带认证头的请求改为未经核实的响应链接。

`@no-log` 不保证服务端、代理、IDE 响应视图或显式保存的下载文件没有敏感内容。下载响应即使失败也可能被保存；先确认状态断言成功，再使用保存的文件。不要将附件、凭据或本地环境文件提交到仓库。

附件 ID 是全局标识，删除接口不带工单 Key。删除确认开关只能防止误操作，不能证明附件归属，也不是服务端授权机制。删除前必须从指定工单列表核实目标。

读取需要工单可见权限；上传需要创建附件权限；删除需要删除自己或所有附件的权限，并受到工单安全级别和 API Token 授权范围限制。`401`、`403`、`404`、`413` 和 `429` 应按实际响应检查认证、权限、目标、大小限制及限流，不盲目重试。上传超时不代表失败，应先查询列表，避免重复附件。

下载接口针对用户当前使用的 Jira Cloud，不保证兼容 Jira Server / Data Center。Cloud scoped API Token 或 OAuth 网关基址需按凭据类型核对，现有 Basic 认证也必须与所用凭据兼容。

## 官方参考

- [Jira Cloud REST API v2 附件接口](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-attachments/)
- [JetBrains HTTP Client 请求语法、文件上传与响应保存](https://www.jetbrains.com/help/idea/exploring-http-syntax.html)
