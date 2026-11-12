[中文](#中文) | [English](#english)

## 中文

### 范围与入口

此目录验证可执行 CLI 的集成行为；`api-test/jira` 保留 REST 接口探索与手工调试。`run.sh` 分发工具套件，`jira/run.sh` 构建一次并运行场景，`common` 保存断言，`jira/cases` 保存场景，`jira/fixtures` 仅保存合成数据。`run-jira.sh` 保留为直接启动 CLI 的兼容入口，不是测试套件。

需要 JDK 25+、Maven 和 Bash（Windows 使用 Git Bash）。真实 Jira 场景还需要 `jq`、HTTPS 连接配置、`JIRA_API_TOKEN` 和专用测试项目中的创建、查看、编辑、评论、附件及删除权限。不提交连接凭据；本地连接配置可放在被忽略的 `integration-test/jira.local.conf` 中。

### 执行

从仓库根目录运行：

```bash
# 默认离线，不读取连接配置，也不访问 Jira
bash integration-test/run.sh jira

# 已构建时跳过构建，只执行指定场景
bash integration-test/run.sh jira --case smoke --skip-build

# 显式授权真实测试；预先安全设置 JIRA_API_TOKEN
bash integration-test/run.sh jira --live \
  --config integration-test/jira.local.conf --project TEST \
  --api-version 2 --type Task
```

`--case` 支持 `all`、`smoke`、`failure-cases`、`issue-lifecycle`、`description-rendering`、`attachments`。默认 `all` 仅执行前两个离线场景；`--live` 才启用远程写入。v2/v3 使用各自连接配置分开运行，`--api-version` 声明预期格式，不覆盖配置。`--type` 必须在测试项目中可创建，可能还需要实例要求的额外字段；当前脚本不支持这些自定义必填字段。

### 断言、安全与结果

真实场景先创建带唯一运行标签的合成问题，通过 CLI JSON 输出读回字段并断言，再编辑、评论或上传合成附件。v2 验证 Wiki 与表格；v3 验证 ADF 标题与加粗，不上传不支持的 ADF 表格。用例共享本次创建的问题，避免操作已有问题。

退出时仅删除内存中记录、属于指定项目的本次 issue key，不使用搜索批量删除，也不级联删除子任务。清理失败会改变成功退出码并生成 `retained-issue.txt`；测试失败退出码保留。创建响应丢失、进程被强制终止或网络中断可能遗留问题，需根据运行标签人工核查，不能保证远程事务回滚。

结果保存在 Git 忽略的 `integration-test/output` 唯一目录，包括摘要、issue key、命令退出码及 stdout/stderr。日志替换令牌原文，不记录命令参数或连接配置；仍可能包含 Jira 返回的用户或项目元数据，须在分享前审核并按本地保留策略删除。合成附件保留在运行目录，远程附件随问题删除。不要开启 `set -x`。默认保留运行结果便于诊断，无自动保留周期。

脚本错误立即失败，无自动重试以免重复写入。真实套件尚未在实际 Jira 实例验证，建议手动或定时触发，不挂到默认 Maven 构建。

## English

### Scope and entry points

This directory verifies executable CLI integration behavior; `api-test/jira` retains REST exploration and manual debugging. `run.sh` dispatches tool suites, `jira/run.sh` builds once and runs scenarios, `common` holds assertions, `jira/cases` holds scenarios, and `jira/fixtures` contains only synthetic data. `run-jira.sh` remains a compatibility launcher for direct CLI use, not a test suite.

Require JDK 25+, Maven, and Bash (Git Bash on Windows). Live Jira scenarios also require `jq`, an HTTPS connection configuration, `JIRA_API_TOKEN`, and create, view, edit, comment, attachment, and delete permissions in a dedicated test project. Do not commit credentials; local configuration can reside in ignored `integration-test/jira.local.conf`.

### Execution

Run from the repository root:

```bash
# Offline by default: no connection configuration or Jira requests
bash integration-test/run.sh jira

# Skip building an existing artifact and select a scenario
bash integration-test/run.sh jira --case smoke --skip-build

# Explicitly authorize live tests; securely set JIRA_API_TOKEN beforehand
bash integration-test/run.sh jira --live \
  --config integration-test/jira.local.conf --project TEST \
  --api-version 2 --type Task
```

`--case` supports `all`, `smoke`, `failure-cases`, `issue-lifecycle`, `description-rendering`, and `attachments`. Default `all` runs only the first two offline scenarios; only `--live` enables remote writes. Run v2/v3 separately with their respective connection configurations; `--api-version` declares the expected format and does not override configuration. `--type` must be creatable in the test project, which might require additional instance-specific fields; the scripts do not currently support such custom required fields.

### Assertions, safety, and results

Live scenarios first create a synthetic issue with a unique run label, read fields back through CLI JSON and assert them, then edit, comment, or upload a synthetic attachment. v2 verifies Wiki and tables; v3 verifies ADF headings and bold without uploading unsupported ADF tables. Scenarios share the newly created issue instead of touching existing issues.

On exit, delete only this run's issue key held in memory and belonging to the specified project, without bulk search deletion or cascading subtask deletion. Cleanup failure changes a successful exit code and creates `retained-issue.txt`; test failure codes are preserved. A lost creation response, forced termination, or network interruption can leave orphan issues; inspect run labels manually. Remote transactional rollback is not guaranteed.

Results reside in a unique, Git-ignored `integration-test/output` directory, including a summary, issue key, command exit codes, and stdout/stderr. Logs replace the literal token and do not record command arguments or connection configuration; Jira user or project metadata may still be present, so review before sharing and delete according to local retention policy. Synthetic attachments remain in the run directory; remote attachments are removed with their issue. Do not enable `set -x`. Results are retained for diagnosis without an automatic retention period.

Script errors fail immediately, without automatic retries that could duplicate writes. The live suite has not been validated against a real Jira instance; trigger it manually or on a schedule rather than through the default Maven build.
