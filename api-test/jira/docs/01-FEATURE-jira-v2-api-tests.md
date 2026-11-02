# Jira REST API v2 接口与 HTTP 测试指南

## 1. 目的与范围

这组测试用于研究 Jira 工单的创建、读取、局部更新、评论、状态流转和 JQL 搜索，为后续自动化工具提供可重复的手动验证入口。

请求使用 JetBrains HTTP Client 格式，包含 JavaScript 前置脚本和响应断言。建议使用较新的 IntelliJ IDEA HTTP Client；其他 REST 客户端不保证支持这些脚本及安全开关。它们不是无人值守的集成测试，也没有实现业务幂等、自动重试或自动清理。

API v2 不等于 Jira 产品版本，也不能据此确定是 Cloud 还是自建 Data Center。旧版 Jira Server 也可能提供 v2；应按实际部署版本核对接口。本文的 Data Center 官方参考使用 10.0.0 文档作为示例，不代表目标实例的版本。

## 2. 文件目录

| 文件 | 内容 |
| --- | --- |
| [测试环境配置](../http-client.env.json) | 地址、项目、工单、分页参数和写入开关 |
| [认证与版本测试](../01-verify/01_auth-and-server.http) | 当前账号及服务器信息 |
| [项目与字段测试](../02-project-info/02_projects-and-fields.http) | 项目、字段、创建元数据 |
| [创建工单测试](../03-issue-management/0301_create-issue.http) | 创建一个测试工单 |
| [读取与更新测试](../03-issue-management/0302_read-issue.http) | 读取、编辑元数据、字段替换及标签追加 |
| [附件管理测试](../04-attachment-management/0401_upload.http) | Jira Cloud 附件查询、上传、下载和显式确认删除；见[附件管理指南](02-FEATURE-jira-issue-attachments.md) |
| [状态流转测试](../05_transitions/05_transitions.http) | 查询可用动作并执行流转 |
| [评论测试](../06_comments.http) | 分页读取和新增评论 |
| [搜索测试](../07_search.http) | 传统搜索及 Cloud 增强搜索 |

原有 `main.http` 保持不变，不作为批量执行入口。

## 3. 环境与认证配置

### 3.1 环境参数

在 HTTP Client 中选择 `jira-v2` 环境。公共配置只提供占位值，默认地址为不可用的示例域名，且禁止写入。

推荐在 `ai-stuff/jira/http-client.private.env.json` 中创建同名 `jira-v2` 环境，覆盖需要修改的参数。该文件名已被仓库根目录的 `.gitignore` 忽略；提交前仍应确认没有敏感信息进入版本控制。

本地覆盖配置示例：

```json
{
  "jira-v2": {
    "jiraBaseUrl": "https://jira.example.invalid/jira",
    "projectKey": "TEST",
    "issueTypeId": "REPLACE_WITH_REAL_TYPE_ID",
    "issueKey": "TEST-123",
    "transitionId": "REPLACE_WITH_REAL_TRANSITION_ID",
    "allowWrites": false
  }
}
```

| 参数 | 说明 |
| --- | --- |
| `jiraBaseUrl` | 实际 Jira 基址，不带末尾 `/`；若部署在 `/jira` 下，必须保留该上下文路径 |
| `projectKey` | 测试项目 Key，而非显示名称 |
| `issueTypeId` | 当前项目支持的工单类型 ID，不要假设固定编号 |
| `issueKey` | 人工确认允许修改的测试工单，例如 `TEST-123` |
| `transitionId` | 当前工单可执行的流转动作 ID，不是状态 ID |
| `allowWrites` | JSON 布尔值；只有 `true` 才允许写请求，字符串 `"true"` 无效 |
| `startAt` | 传统分页起始偏移，在环境文件中保存为数字字符串 `"0"` |
| `maxResults` | 请求页大小，在环境文件中保存为数字字符串 `"50"`；服务端可能进一步限制 |
| `nextPageToken` | Cloud 增强搜索下一页令牌，仅有后续页时填写 |

从 `01-verify` 子目录执行请求时，也应选择父目录配置提供的 `jira-v2` 环境。若 IDE 未识别，检查 HTTP Client 环境文件发现与选择功能。

### 3.2 认证头

所有请求通过 `{{$env.JIRA_AUTHORIZATION}}` 从运行 IDE 的进程环境读取 `JIRA_AUTHORIZATION`，变量值必须是完整的 Authorization 头值：

| 场景 | 头值形式 | 注意事项 |
| --- | --- | --- |
| 支持 PAT 的 Data Center | `Bearer <PAT>` | 是否支持以及如何申请取决于产品版本和管理员配置 |
| 支持 Basic 的部署 | `Basic <Base64编码结果>` | 编码内容取决于部署；Base64 不是加密 |
| Cloud 的传统 API Token Basic 方式 | `Basic <邮箱:API Token的Base64编码结果>` | 不能用网页登录密码替代 API Token |
| OAuth | `Bearer <access_token>` | 需要匹配授权范围、令牌有效期和 API 基址 |

通过操作系统或 IDE 启动环境安全设置该变量，且不要在共享终端历史、截图或仓库中记录实际值。已经运行的 IDE 不会自动继承另一个新终端中的环境变量；修改后需要按实际启动方式重新加载环境。不要通过日志打印该变量来排查问题。

Cloud 的部分 scoped API Token 或 OAuth 访问需要使用 `https://api.atlassian.com/ex/jira/{cloudId}` 这类网关基址，应把完整基址配置到 `jiraBaseUrl`，不能只替换 Token。具体以所用凭证类型的官方说明为准。

只使用可信的 HTTPS 地址，使用最小权限测试账号。网页 SSO 登录成功不意味着 REST 认证可用。收到重定向或 HTML 登录页时，应排查网关和认证配置，而不是将其视为成功。

### 3.3 安全执行约束

- 所有请求设置 `@no-redirect`，避免自动跟随 SSO 或跨站重定向。
- 所有请求设置 `@no-log`，减少请求进入 HTTP Client 历史记录的风险；这不保证代理、服务端或 IDE 的所有输出都不记录数据。
- 创建、更新、评论新增和状态流转均有前置脚本，`allowWrites` 不是布尔值 `true` 时直接中止。
- 写入开关是测试辅助保护，不是权限隔离或生产安全边界；不要删除或绕过前置脚本。
- 不要执行整个目录或全部请求。元数据和搜索存在互斥的版本示例，写请求也可能重复产生数据。
- 流转动作可能触发通知、自动化规则或外部集成。即使在测试项目，也应先确认影响。
- 测试结束后将 `allowWrites` 改回 `false`。不提供删除工单、删除评论等破坏性测试，测试数据由人工确认后清理。

## 4. 推荐验证顺序

1. 保持 `allowWrites: false`，执行 `myself`，确认返回的账号符合预期。
2. 执行 `serverInfo`，记录版本和部署信息；该信息只用于辅助判断兼容性。
3. 查询项目和字段，选择真实 `issueTypeId`。
4. 选择目标版本支持的创建元数据接口，检查所有必填字段和允许值。
5. 检查创建请求体；若某字段不可用，删除该字段，若存在额外必填项，则补齐。
6. 确认目标是测试项目后，将 `allowWrites` 设置为布尔值 `true`，只执行一次创建请求。
7. 创建成功后查看响应。脚本保存 `createdIssueKey`、`createdIssueId` 全局变量，但不会自动覆盖 `issueKey`。
8. 人工核实新工单后，将其 Key 填入环境的 `issueKey`，读取工单和 `editmeta`。
9. 分别执行字段更新、标签追加、评论新增，再通过读取请求核对结果。
10. 查询可用 transitions，选择动作 ID 并检查其必填字段后，按需执行流转。
11. 选择与部署匹配的搜索请求，验证工单可见性和分页。刚写入的数据可能尚未进入搜索索引。
12. 恢复禁止写入状态，按团队规则清理测试数据。

## 5. 接口说明

以下路径均相对于 `jiraBaseUrl`。查询与写入结果受到项目权限、工单安全级别、字段上下文和工作流限制。

### 5.1 身份、项目与字段

| 方法 | 路径 | 预期成功状态 | 说明 |
| --- | --- | --- | --- |
| GET | `/rest/api/2/myself` | `200` | 验证认证并读取当前账号 |
| GET | `/rest/api/2/serverInfo` | `200` | 查看版本等服务器信息 |
| GET | `/rest/api/2/project/{projectIdOrKey}` | `200` | 读取项目；检查响应中可用的工单类型信息 |
| GET | `/rest/api/2/field` | `200` | 获取当前账号可见的系统与自定义字段定义 |

`/field` 并不说明某项目、某工单类型有哪些必填字段。自定义字段通常使用 `customfield_12345` 之类的 ID；不同实例、项目上下文和选项 ID 不能直接复用。

### 5.2 创建元数据的版本差异

| 形式 | 路径 | 说明 |
| --- | --- | --- |
| 传统聚合接口 | `/rest/api/2/issue/createmeta` | 用 `projectKeys`、`issuetypeIds` 和 `expand=projects.issuetypes.fields` 缩小结果；Cloud 已标记弃用，是否可用必须核实 |
| 分页工单类型 | `/rest/api/2/issue/createmeta/{projectIdOrKey}/issuetypes` | 获取指定项目可创建的类型；需确认部署版本支持 |
| 分页创建字段 | `/rest/api/2/issue/createmeta/{projectIdOrKey}/issuetypes/{issueTypeId}` | 获取指定类型的字段元数据；需确认部署版本支持 |

支持的元数据请求预期返回 `200`。检查字段中的 `required`、`schema`、`allowedValues`、`operations` 等实际返回内容。元数据为空不等于没有必填项，也可能是项目、类型或权限不匹配。

分页接口应读取所有页，不要只检查第一页。不同版本的集合属性和分页响应可能不同，应遵循实际响应及对应版本文档，不强制假设统一的集合属性名。

### 5.3 创建工单

`POST /rest/api/2/issue`

测试请求使用 `fields` 包含 `project.key`、`issuetype.id`、`summary`、字符串 `description` 和标签数组 `labels`。这是需要按项目调整的基础模板，不保证适用于所有项目。

成功通常返回 `201`，包含 `id`、`key` 和 `self`。测试断言检查状态码和工单标识，并在成功后保存新工单 ID 与 Key。每次创建前会清除上次保存的这两个变量，避免失败后误用旧结果。

负责人和报告人没有预设：Cloud 通常使用 `accountId`，Data Center 的标识方式需要按版本确认。子任务还需要正确的子任务类型及父工单；本测试不包含子任务创建。

### 5.4 读取与局部更新

| 方法 | 路径 | 预期成功状态 |
| --- | --- | --- |
| GET | `/rest/api/2/issue/{issueIdOrKey}` | `200` |
| GET | `/rest/api/2/issue/{issueIdOrKey}/editmeta` | `200` |
| PUT | `/rest/api/2/issue/{issueIdOrKey}` | `204` |

读取示例通过 `fields` 限制返回字段。`editmeta` 用于确认当前账号、当前工单下可编辑的字段及支持操作。

更新文件提供两个独立请求：

- `fields`：替换标题和描述的值，会覆盖这两个字段现有内容。
- `update.labels` 中的 `add`：在字段支持该操作时追加标签，避免替换整个标签集合。

不要将整个查询响应原样用于更新，也不要在同一个请求中通过 `fields` 和 `update` 重复操作同一字段。更新成功的 `204` 通常没有响应体，脚本不会尝试解析不存在的 JSON。

这些测试未实现并发冲突检测。查询后再更新不构成锁；实际自动化工具应明确字段所有权，必要时进行冲突检测和人工确认。

### 5.5 状态流转

| 方法 | 路径 | 预期成功状态 |
| --- | --- | --- |
| GET | `/rest/api/2/issue/{issueIdOrKey}/transitions?expand=transitions.fields` | `200` |
| POST | `/rest/api/2/issue/{issueIdOrKey}/transitions` | `204` |

查询响应中的 `transitions[].id` 才是提交时的 `transition.id`。目标状态 ID、状态名称和动作 ID 不能混用。

空的 `transitions` 数组可以是正常结果，例如当前账号没有可执行动作。若动作要求填写 resolution 等字段，应根据该动作返回的字段元数据补充请求体；基础模板只提交 `transition.id`。不要猜测或硬编码 resolution 等字段的选项值。

工作流可能在查询后发生变化，所以执行时仍可能被拒绝。成功后重新读取工单，验证实际状态和副作用。

### 5.6 评论

| 方法 | 路径 | 预期成功状态 |
| --- | --- | --- |
| GET | `/rest/api/2/issue/{issueIdOrKey}/comment` | `200` |
| POST | `/rest/api/2/issue/{issueIdOrKey}/comment` | `201` |

读取使用 `startAt` 和 `maxResults`。新增使用 v2 字符串 `body`，成功响应包含评论 ID。描述和评论的实际渲染规则取决于部署和字段配置，不直接套用 v3 的 ADF 对象。

模板不设置评论可见性限制，可能通知关注者或暴露给工单可见用户。不得写入密码、Token 或敏感业务数据。每次执行都会新增评论；若目标项目需要限定评论可见范围，应先按部署文档调整请求。

### 5.7 JQL 搜索与分页

| 场景 | 方法与路径 | 分页方式 |
| --- | --- | --- |
| Data Center 等支持传统接口的部署 | `GET /rest/api/2/search` | `startAt`、`maxResults` |
| 同上，适合较长查询 | `POST /rest/api/2/search` | JSON 中的 `startAt`、`maxResults` |
| 支持增强搜索的 Cloud | `POST /rest/api/2/search/jql` | `nextPageToken` |

搜索成功预期返回 `200` 和 `issues` 数组。搜索用的 POST 是只读请求，不受写入开关限制。

Cloud 的传统 `/search` 接口已被官方标记为正在移除，不应作为新 Cloud 集成的默认选择。增强 `/search/jql` 也不是所有 Data Center 版本通用的接口。

传统分页应依据响应的 `startAt`、`maxResults`、`total` 和实际返回条数推进；不能假定请求的页大小就是实际页大小。搜索期间发生新增和排序变化时，偏移分页可能出现重复或遗漏，生产同步需要稳定排序和去重策略。

Cloud 增强搜索首次请求不带令牌。只有响应表明还有后续页并提供令牌时，才将令牌复制到 `nextPageToken`，执行下一页请求。后续页保持相同查询和字段参数；到末页后停止，不能将 `startAt` 分页逻辑混入令牌分页。

GET 示例对固定 JQL 进行 URL 编码；POST 示例中的 JQL 直接使用 JSON 字符串，不额外 URL 编码。当前 `projectKey` 占位针对常规项目 Key，不能把不可信外部文本未经转义地拼入 JQL。

搜索索引可能延迟更新。确认刚创建的工单时优先用其 Key 直接读取，不要因暂时搜索不到就再次创建。

## 6. 常见错误与重试原则

| 状态或现象 | 常见原因 | 处理方式 |
| --- | --- | --- |
| `400` | 必填缺失、字段不允许、选项 ID 错误、JQL 无效 | 检查响应 `errors`、`errorMessages` 和元数据，不盲目重试 |
| `401` | 认证缺失、Token 过期或格式错误 | 检查凭证类型和完整 Authorization 头 |
| `403` | 项目权限、授权范围、工作流或安全策略限制 | 检查账号权限，不通过换字段绕过限制 |
| `404` | 路径错误、对象不存在或账号不可见 | 同时核查基址、版本、标识和权限 |
| `405`、`410` | 方法不支持，或旧接口已停用 | 核对部署版本和官方替代接口 |
| `429` | 限流 | 遵循响应中的 `Retry-After` 等指引，并实施有上限的退避 |
| `5xx`、网络中断、超时 | 服务或网络异常 | 区分读写；写操作可能已经生效，先核对结果 |
| `3xx` 或 HTML 登录页面 | SSO、代理、上下文路径或认证问题 | 不视为成功，不自动跟随到其他站点 |
| 证书校验失败 | 内网 CA 或证书链未被信任 | 正确配置可信证书，不全局关闭 TLS 校验 |

创建工单、添加评论和执行状态流转通常不能安全地盲目重发。重复设置相同字段值也可能触发通知或规则，不能简单把 PUT 当作没有副作用的重试。

后续工具应为来源事件建立稳定业务 ID，持久化其与 Jira Key 的映射，并控制并发。仅“先搜索再创建”无法解决搜索延迟和并发竞态；这些 HTTP 示例不提供业务幂等保证。

## 7. 验证范围与限制

响应脚本检查预期状态码，部分请求还检查响应结构。元数据接口的版本兼容性和写入后的业务结果仍需人工确认；返回 `2xx` 并不代表所有业务预期都已满足。

这些文件未提供真实 Jira 地址或凭证，交付时只进行本地静态校验，没有完成与实际实例的联网验证。开始使用前，应在测试项目逐个执行，而不是直接用于生产自动化。

## 8. 官方参考

- [Jira Cloud v2 工单接口与创建元数据](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issues/)
- [Jira Cloud v2 评论接口](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-comments/)
- [Jira Cloud v2 JQL 搜索接口](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-search/)
- [Jira Cloud Basic Authentication 说明](https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/)
- [Jira Data Center 10.0.0 工单接口参考](https://developer.atlassian.com/server/jira/platform/rest/v10000/api-group-issue/)
- [Jira Data Center 10.0.0 搜索接口参考](https://developer.atlassian.com/server/jira/platform/rest/v10000/api-group-search/)
- [JetBrains HTTP Client 使用指南](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html)
