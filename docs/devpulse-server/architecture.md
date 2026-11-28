# devpulse-server 架构 / devpulse-server architecture

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

## 中文

### 位置

```
devpulse-execution ──┐
devpulse-storage  ───┴─→ devpulse-server ──→ devpulse（server 子命令）
```

服务端是执行与持久化的唯一拥有者。客户端只走 HTTP，不打开数据库、不启动进程。

### 分层

| 层 | 类 | 职责 |
|---|---|---|
| 设置 | `ServerSettings` | 绑定地址、端口、状态文件、doctor 目录、中央库路径 |
| 路径 | `ServerPaths` | `~/.devpulse` 下 `server.json` 与 `server.mv.db` 的位置 |
| 认证 | `Auth` | 客户头与 Host 两个纯函数校验 |
| 状态 | `ServerState` | `{pid, port, bootId, version, startedAt}` 的读写 |
| 存储 | `JdbiRunStore`、`WorkflowStore` | 中央库连接池、workflow head 与不可变 revision |
| 装配 | `ServerBootstrap` | 建 MuServer、注册路由、守卫每个请求、启动后写状态文件 |

`Auth` 与 `ServerState` 不依赖 socket，可以在没有网络的测试里覆盖。

### workflow 注册表

启动时打开 `~/.devpulse/server.mv.db`（连接池）并迁移 schema。服务端是 workflow 内容的真相源，项目里的 `.workflow/<id>.yaml` 是数字孪生：

- `GET /api/workflows`、`GET /api/workflows/{id}`（可带 `revision=`）、`GET /api/workflows/{id}/revisions` 读；
- `PUT /api/workflows/{id}` 写：每次推送追加一条不可变 revision 并前移 head，旧 revision 永不改写；
- `baseRevision` 过期返回 `409 WORKFLOW_CONFLICT` 与当前 head，调用方选择 `--force` 覆盖或先 `pull`。

CLI 本地运行把 workflow id、路径与内容 SHA-256 写入 run 记录；`workflow_revision` 在服务端提交路径接通前为 `null`。

### 与参考实现的关系

结构参考 host-helper（App → AppContext → Bootstrap → Handler + 统一响应体），但：

- 不引入 zora-sso、zora-rdb 等库；
- 认证改为本地 token；
- 配置先由命令行参数承载，HOCON 配置层等出现第二个配置来源时再加。

### 原生镜像

服务端在 GraalVM CE 25.0.2 下验证通过（`native.sh --verify` 11 项 + 原生 `server run` 端到端）。状态文件刻意手写 JSON，避免 Gson 的 record 反射；新增命令或 record 后需重新录制反射元数据。

### 响应与错误

成功返回业务 JSON；失败统一 `{"error":{"code","message"}}`，`code` 复用 CLI 的失败分类（见 [服务端接口设计](../design/server-api.md)）。HTTP 状态只表达"请求是否成功"，任务失败属于运行结果。

## English

### Position

The server owns execution and persistence. Clients speak HTTP only: they never open the database and never spawn a task process.

### Layers

`ServerSettings` (bind address, port, state file, doctor directory, central database), `ServerPaths` (`server.json` and `server.mv.db` under `~/.devpulse`), `Auth` (two pure checks), `ServerState` (pid, port, boot id, version, started time), `JdbiRunStore` and `WorkflowStore` (the pooled central database, workflow heads and immutable revisions), and `ServerBootstrap` (build MuServer, register routes, guard every request, write the state file). `Auth` and `ServerState` touch no socket, so tests cover them without a network.

### Workflow registry

The server opens `~/.devpulse/server.mv.db` pooled and migrates it at startup. It is the source of truth for workflow content; a project's `.workflow/<id>.yaml` is a twin: `GET /api/workflows`, `GET /api/workflows/{id}` (optionally `revision=`) and `GET /api/workflows/{id}/revisions` read; `PUT /api/workflows/{id}` appends an immutable revision and moves the head, never rewriting an old one. A stale `baseRevision` returns `409 WORKFLOW_CONFLICT` with the current head, so the caller picks `--force` or pulls first. A local CLI run records the workflow id, path and content SHA-256 on the run; `workflow_revision` stays `null` until the server submission path lands.

### Relation to the reference implementation

The layering follows host-helper without its company libraries: a local token instead of zora-sso, and command-line configuration until a second configuration source appears.

### Native image

The server is verified under GraalVM CE 25.0.2 (the native smoke suite plus a native `server run` round trip). The state file is written as plain JSON to avoid Gson record reflection, and new commands or records require re-recording the metadata.

### Responses and errors

Success returns the business JSON; failures return `{"error":{"code","message"}}` with codes reused from the CLI's failure classes. HTTP status says whether the request succeeded; a failed task is a run outcome.
