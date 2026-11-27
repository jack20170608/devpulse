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
| 设置 | `ServerSettings` | 绑定地址、端口、token、状态文件路径、doctor 目录 |
| 路径 | `ServerPaths` | `~/.devpulse` 下 token 与状态文件的位置 |
| 认证 | `ServerToken`、`Auth` | 生成/读取 token；Bearer 与 Host 两个纯函数校验 |
| 状态 | `ServerState` | `{pid, port, bootId, version, startedAt}` 的读写 |
| 装配 | `ServerBootstrap` | 建 MuServer、注册路由、守卫每个请求、启动后写状态文件 |

`Auth` 与 `ServerState` 不依赖 socket，可以在没有网络的测试里覆盖。

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

`ServerSettings` (bind address, port, token, state file, doctor directory), `ServerPaths` (the two files under `~/.devpulse`), `ServerToken` and `Auth` (token creation plus two pure checks), `ServerState` (pid, port, boot id, version, started time), and `ServerBootstrap` (build MuServer, register routes, guard every request, write the state file). `Auth` and `ServerState` touch no socket, so tests cover them without a network.

### Relation to the reference implementation

The layering follows host-helper without its company libraries: a local token instead of zora-sso, and command-line configuration until a second configuration source appears.

### Native image

The server is verified under GraalVM CE 25.0.2 (the native smoke suite plus a native `server run` round trip). The state file is written as plain JSON to avoid Gson record reflection, and new commands or records require re-recording the metadata.

### Responses and errors

Success returns the business JSON; failures return `{"error":{"code","message"}}` with codes reused from the CLI's failure classes. HTTP status says whether the request succeeded; a failed task is a run outcome.
