# 服务端接口设计（阶段 3 评审稿）/ Server API design (phase 3 review)

**状态 / Status**：评审稿，未实现 / Review draft, not implemented

[项目首页](../../readme.MD) | [中文](#中文) | [English](#english)

产品形态见 [product-shape.md](product-shape.md)；存储模型见 [client-server-model.md](client-server-model.md)。

## 中文

### 1. 进程模型

```text
devpulse server run      前台运行，日志输出到终端
devpulse server start    后台启动：spawn 自身 `server run`，等待就绪
devpulse server status   读锁文件并做一次健康检查
devpulse server stop     请求优雅停止，超时后退出
```

单实例，每个用户一份：

| 项 | 内容 |
|---|---|
| 锁文件 | `~/.devpulse/server.json`，含 `pid`、`port`、`bootId`、`version`、`startedAt` |
| 端口 | 绑定 `127.0.0.1:0`，由系统分配，写回锁文件（避免端口冲突） |
| 数据 | 中央库 `~/.devpulse/server.mv.db`（v3 单库） |
| 大文件 | 仍留在项目的 `.devpulse/runs/<id>/`，服务端只写路径 |

陈旧锁的处理：`status` 发现 pid 不存在或健康检查失败，视为未运行并清理锁文件。

### 2. 传输与认证

| 项 | 决定 |
|---|---|
| 协议 | loopback HTTP/1.1 + JSON；事件用 SSE |
| 绑定 | 仅 `127.0.0.1` |
| 认证 | **无**（单人本机工具，不加账号与 token） |
| 防 DNS rebinding | 校验 `Host` 头必须是 `127.0.0.1:<port>` 或 `localhost:<port>` |
| 版本握手 | 请求头 `X-Devpulse-Version`；0.x 阶段要求完全一致，否则 409 |

不做：远程访问、TLS、多用户权限。首版只有"本机同一用户"。

### 3. 端点

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/health` | `{version, bootId, uptimeMs, database}` |
| GET | `/api/doctor` | 环境报告（等价于 CLI `doctor --format json`） |
| GET | `/api/projects` | 已登记项目列表 |
| POST | `/api/runs` | 提交运行，返回 `202` 与 `{runId, state}` |
| GET | `/api/runs?project=&state=&limit=&offset=` | 分页列表（复用 `RunQueries`） |
| GET | `/api/runs/{id}` | 单次运行详情（等价于 `status --run --format json`） |
| GET | `/api/runs/{id}/events?after=<seq>` | SSE 事件流 |
| GET | `/api/runs/{id}/logs/{task}?offset=&limit=` | 任务日志片段 |
| POST | `/api/runs/{id}/cancel` | 取消运行，幂等，返回 `202` |
| POST | `/api/plan` | 静态计划（等价于 `review` / `--dry-run`） |
| POST | `/api/daemons/{instanceId}/stop` | 停止长驻进程 |

请求示例：

```json
POST /api/runs
{
  "directory": "/home/me/project",
  "target": "dev",
  "jobs": 4,
  "dryRun": false
}
```

`/api/plan` 返回与运行相同的计划结构，但不创建运行记录、不执行任务。

### 4. 错误模型

错误体统一为：

```json
{"error": {"code": "CONFIG_GRAPH", "message": "cycle: build -> test -> build"}}
```

`code` 直接复用 CLI 的分类（`ExitCodes` 的语义名，不重新发明）：

| code | HTTP | 典型原因 |
|---|---|---|
| `USAGE` | 400 | 参数不可用 |
| `CONFIG_INVALID` | 422 | 配置读不了或整体校验失败 |
| `CONFIG_GRAPH` | 422 | 依赖图成环或字段非法 |
| `TARGET_INVALID` | 404 | 目标不存在 |
| `ENVIRONMENT` | 409 | 缺 Bash 等外部能力 |
| `RETRY_REFUSED` | 409 | 状态不允许该操作 |
| `VERSION_MISMATCH` | 409 | 客户端与服务端版本不一致 |
| `TASK_FAILED` / `DAEMON_*` | — | 不作为 HTTP 错误返回，它们是运行结果 |
| `INFRASTRUCTURE` | 500 | 存储或文件写入失败 |
| `INTERRUPTED` | 503 | 服务端正在关闭 |

**运行失败不是请求失败**：提交成功后返回 `202`，任务失败的结论体现在运行状态与 SSE 结束事件里。这一点与 CLI 退出码的语义一致。

### 5. 事件流

SSE，`id` 即持久化序号：

```text
id: 17
event: task.finished
data: {"runId":"r1","task":"build","state":"SUCCESS","exitCode":0,"at":"2026-11-26T14:00:00Z"}
```

| 事件 | 时机 |
|---|---|
| `run.queued` | 已接受，尚未开始 |
| `run.started` | 开始执行 |
| `task.started` | 任务开始 |
| `task.finished` | 任务结束（含失败、跳过、取消） |
| `run.finished` | 运行结束，含最终状态与计数 |

约定：

1. **事件必须持久化**。`run_event` 在 v3 已建表但未接线，阶段 3 接线；否则客户端断线重连无法续读。
2. `GET events?after=17` 与 `Last-Event-ID: 17` 等价，先补发历史再转实时。
3. 事件只描述状态变化，**不承载日志正文**；日志走 `/logs`。

### 6. 并发与生命周期

| 问题 | v1 决定 |
|---|---|
| 同一项目并发运行 | 允许，但同一项目同一时间只保留一个"活动运行"，第二个返回 409 |
| 不同项目并发 | 允许（服务端内部仍受 `jobs` 限制） |
| 客户端断开 | 运行继续；事件照常写入 |
| 服务端重启 | 运行中的记录标记为 `INTERRUPTED`，不自动续跑 |
| 长驻进程 | 服务端重启后不自动停止，仍由用户 `stop` |

### 7. 客户端如何用

```text
devpulse run dev
  → 带 `X-Devpulse-Client` 头
  → POST /api/runs
  → GET /api/runs/{id}/events 跟随
  → 断线：带 Last-Event-ID 重连
  → run.finished：映射为 CLI 退出码（SUCCESS → 0；失败 → 30 等）
```

`review` 与 `--dry-run` 走 `POST /api/plan`；`status` 走 `GET /api/runs`；`doctor` 先本地诊断再 `GET /api/doctor`。

### 8. Web UI（首版只读）

服务端同时提供本地 Web 界面，与 CLI 共用同一份 HTTP API：

| 页面 | 内容 |
|---|---|
| 运行列表 | 按项目/状态过滤，分页 |
| 运行详情 | 任务表、状态、耗时、退出码 |
| 日志 | 任务日志尾读（`/api/runs/{id}/logs/{task}`） |
| 环境 | `doctor` 报告 |

首版**不包含**触发运行、取消运行、编辑配置；这些交互等到 API 的提交语义稳定后再做。

### 9. v1 明确不做

远程访问与 TLS、多用户与权限、SSE 推送日志正文、运行期插件热加载、运行队列与优先级。

## English

### 1. Process model

`devpulse server run` stays in the foreground; `start` spawns that child and waits for readiness; `status` reads the lock file and probes health; `stop` asks for a graceful shutdown. There is one instance per user, described by `~/.devpulse/server.json` (pid, port, bootId, version, startedAt). The server binds `127.0.0.1:0` and records the assigned port, so a second install never fights over a fixed port. The central database lives at `~/.devpulse/server.mv.db`; run directories stay inside each project.

A stale lock is a lock whose pid is gone or whose health probe fails; `status` reports it as not running and clears it.

### 2. Transport and authentication

Loopback HTTP/1.1 with JSON and SSE, bound to `127.0.0.1` only, on port 12580 by default; a busy port is an error rather than a silent fallback. There is no authentication: the product serves one local user. The server validates `Host` against the loopback names and port, and every `/api` request must carry `X-Devpulse-Client`, which a cross-site form cannot set without a preflight. `X-Devpulse-Version` must match exactly while the product is 0.x. Remote access, TLS and multi-user permissions are out of scope.

### 3. Endpoints

Health, doctor, projects, submit a run, list runs, run detail, events, task logs, cancel, plan, and stopping a daemon. Submission returns `202` with the run id; the plan endpoint shares the run payload but creates no record and executes nothing.

### 4. Errors

Every failure carries `{"error": {"code, message"}}`. The code reuses the CLI's failure classes — `USAGE`, `CONFIG_INVALID`, `CONFIG_GRAPH`, `TARGET_INVALID`, `ENVIRONMENT`, `RETRY_REFUSED`, `INFRASTRUCTURE`, `INTERRUPTED` — plus `VERSION_MISMATCH`. A failed task is not an HTTP error: the request succeeded, and the outcome appears in the run state and the closing event.

### 5. Events

SSE frames carry the persistent sequence as `id` and a type in `event`: `run.queued`, `run.started`, `task.started`, `task.finished`, `run.finished`. Events must be persisted in `run_event` (created in v3, not yet wired); otherwise a reconnecting client cannot replay what it missed. `?after=<seq>` and `Last-Event-ID` are equivalent: replay first, then continue live. Events describe state changes only; log bodies travel through the logs endpoint.

### 6. Concurrency and lifecycle

Different projects run concurrently; one project keeps a single active run, and a second submission answers 409. A disconnected client never stops a run. On restart, in-flight runs become `INTERRUPTED` and are not resumed automatically, and daemons are not stopped.

### 7. Client behaviour

`run` submits, follows the event stream, reconnects with the last event id, and maps the final state to the existing exit codes. `review` and `--dry-run` use the plan endpoint; `status` lists runs; `doctor` runs local diagnostics first and then fetches the server report.

### 8. Out of scope for v1

Remote access and TLS, multi-user permissions, log bodies over SSE, a web UI, runtime plugin loading, run queues and priorities.
