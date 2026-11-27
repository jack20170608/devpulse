# devpulse-server 使用说明 / devpulse-server usage

[中文](#中文) | [English](#english)

## 中文

### 这个模块是什么

本地常驻服务端的实现：一个只绑定回环地址的 HTTP 服务，负责执行任务、写中央数据库并向客户端广播事件。

它不注册 CLI 命令；`devpulse server run|status|stop` 由统一入口提供。

### 已实现（阶段 3 第一刀）

| 项 | 内容 |
|---|---|
| 端点 | `GET /api/health`、`GET /api/doctor` |
| 认证 | **无**（单人本机工具）；以 Host 校验 + `X-Devpulse-Client` 头防跨站与 DNS rebinding |
| Host 校验 | 只接受 `127.0.0.1:<port>`、`localhost:<port>`、`[::1]:<port>` |
| 端口 | 默认 **12580**；被占用时报错并提示 `--port` |
| 状态文件 | `{pid, port, bootId, version, startedAt}`，`devpulse server status` 读取 |

### 认证为何不用 SSO

参考实现 host-helper 面向多用户部署，需要 zora-sso；devpulse 首版只有"本机同一用户"，因此用回环绑定 + Host 校验 + 自定义请求头即可，不引入账号、会话与角色。

### 运行

```bash
devpulse server start        # 后台启动，就绪后返回（默认 12580）
devpulse server run          # 前台运行（调试用）
devpulse server status       # 是否在运行、端口、版本
devpulse server stop         # 请求停止（SIGTERM）
```

### 原生镜像

服务端依赖 MuServer（Netty），已在 GraalVM CE 25.0.2（`GRAALVM_HOME` 指向本机 `runtime/graalvm`）下验证：

| 检查 | 结果 |
|---|---|
| `bash native.sh --verify` | 11 项冒烟全过（含 MuServer 类链接进镜像） |
| 原生 `server run` | 启动、写状态文件、`/api/health` 200、缺客户端头 403、`/api/doctor` 正常、`server stop` 退出 |

两点实现约束：

1. 状态文件**手写 JSON**，不用反射式序列化——Gson 的 record 支持在 native 下需要额外注册。
2. 新增命令或 record 后，必须用 GraalVM agent 重新录制反射元数据，否则 native 版只在该路径运行时失败。

## English

### What this module is

The implementation of the local daemon: an HTTP server bound to loopback that executes tasks, owns the central database and broadcasts events.

It registers no CLI command; `devpulse server run|status|stop` come from the unified entry point.

### Implemented (first slice of phase 3)

`GET /api/health` and `GET /api/doctor`, with **no authentication**: this is a single-user local tool. The server binds port 12580 by default, validates the `Host` header against the loopback names and port, requires `X-Devpulse-Client` on every request, and records pid, port, boot id, version and start time in `~/.devpulse/server.json`, which `devpulse server status` reads.

The reference project authenticates with zora-sso because it serves multiple users; this version serves one local user, so a loopback bind plus those two checks is the boundary.

### Native image

The server uses MuServer (Netty) and is verified under GraalVM CE 25.0.2: the native smoke suite passes all eleven checks, and a native `server run` starts, writes its state file, answers `/api/health` (200 with the client header, 403 without), serves `/api/doctor`, and stops on request.

Two implementation constraints follow: the state file is written as plain JSON rather than through a reflective mapper, and new commands or records require re-recording the reflection metadata with the GraalVM agent — otherwise the native build succeeds and fails only on that path at runtime.
