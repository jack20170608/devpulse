# 配置体系设计 / Configuration design

[中文](#中文) | [English](#english)

## 中文

### 1. 问题

当前配置没有体系，四件事各走各的：

| 配置 | 形式 | 位置 | 读取方 |
|---|---|---|---|
| 任务定义 | YAML | 项目根 `.devpulse.yaml` | `PulseConfiguration` |
| Jira 连接 | HOCON | `--config` / `./.jira/` / `~/.jira/` | `JiraConfiguration` |
| 服务端参数 | 仅命令行 | `--port`、`--directory` | `ServerCommand` |
| 日志 | XML | classpath `logback.xml` | logback |

后果：服务端参数无处可存（每次都要敲）；三种格式并存；日志级别改了要重新打包。

### 2. 职责边界

两类文件的**生命周期不同**，必须分开：

| | workflow 文件 | 应用配置 |
|---|---|---|
| 谁产生 | 用户写给客户端 | 用户写给本机 |
| 谁消费 | 作为 payload **发给服务端** | 客户端自己读 |
| 是否进 git | 是（随项目走） | 否（本机偏好） |
| 换机器 | 原样带走 | 重建 |
| 格式 | YAML | HOCON |

结论：workflow 文件里**不出现任何服务端或本机配置**；应用配置里**不出现任务定义**。

### 3. 文件与目录

```text
project/
├── .workflow/
│   ├── build.yaml          # 一个文件 = 一个 workflow
│   └── deploy.yaml
└── .devpulse/              # 运行时状态（生成物，gitignored）
```

```text
~/.devpulse/
├── config.conf             # 唯一的应用配置文件（HOCON）
└── server.json             # 服务端运行状态（程序写，不手改）
```

**一个工具一份配置文件**，默认位置固定，`--config <path>` 覆盖。不扫描多个目录、不做项目级与用户级的层叠合并。

这条规则与现有 Jira 一致：Jira 已经是"默认路径 + `--config` 覆盖"的单个 HOCON 文件，本设计把服务端对齐到同一模式。

`.devpulse/` 是**输出**（运行状态），`workflows/` 与 `config.conf` 是**输入**，三者不混放。

### 4. workflow 文件

文件名即标识：`.workflow/build.yaml` 的 workflow id 是 `build`。

```yaml
name: 构建流水线          # 可选，仅用于展示；缺省等于文件 stem
env:                      # 可选，注入本文件所有任务
  NODE_ENV: ci

tasks:
  - id: compile
    kind: bash
    spec:
      run: |
        pnpm install
        pnpm build

  - id: test
    kind: bash
    needs: [compile]
    spec:
      run: pnpm test
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `name` | 否 | 展示名；**不参与寻址**，id 永远是文件 stem |
| `env` | 否 | 合并进每个任务；任务级 `env` 优先 |
| `tasks` | 是 | 非空列表 |
| `tasks[].id` | 是 | 文件内唯一；`^[a-z][a-z0-9_-]*$` |
| `tasks[].kind` | 是 | 执行器种类；首版只有 `bash`，未知 kind 报错 |
| `tasks[].needs` | 否 | 依赖的任务 id；缺省无依赖 |
| `tasks[].spec` | 是 | 该 kind 的私有数据 |

**校验规则**（服务端在提交阶段执行，失败不产生 run 记录）：

- 只允许单个 YAML 文档；未知顶层字段报错（拼写错误不能被静默忽略）
- `id` 重复、`needs` 指向不存在的 id、DAG 有环 → 报错并指出位置
- 文件为空或无 `tasks` → 报错

一个文件一个 DAG，**不跨文件依赖**。

### 5. 组合（阶段 2）

复用（"所有流水线先跑 lint"）用任务类型表达，不做全局注册表：

```yaml
  - id: lint
    kind: workflow
    spec:
      file: lint.yaml      # 相对当前文件解析
```

路径相对**引用方文件**解析；循环引用在提交阶段检测；被引用文件走同一套校验。

首版**不实现** `kind: workflow`，遇到时明确报"暂不支持"，不静默跳过。

### 6. 命令面

| 命令 | 参数 | 行为 |
|---|---|---|
| `devpulse run build` | workflow id | 找 `.workflow/build.yaml` |
| `devpulse run .workflow/build.yaml` | 路径 | 直接使用该文件 |
| `devpulse run` | 无 | 列出 `.workflow/` 下可用 workflow，退出码 2 |
| `devpulse review build` | 同 `run` | 只解析建图、不执行 |
| `devpulse init build` | workflow id | 生成 `.workflow/build.yaml` 模板 |
| `devpulse server start --config ./dev.conf` | 配置文件路径 | 使用该配置启动 |

寻址规则：参数含路径分隔符或以 `.yaml` 结尾 → 按路径处理；否则按 id 在 `.workflow/` 下查找。两者都找不到时报错并列出候选。

`.devpulse.yaml` 不再被读取；`run` 不再有 target 概念。

### 7. 应用配置

单文件 HOCON，全部键都有默认值：

```hocon
devpulse {
  workflows {
    dir = ".workflow"             # 相对项目目录
  }

  server {
    host = 127.0.0.1
    port = 12580
  }

  run {
    directory = "."               # 服务端 doctor 报告的项目目录
  }

  log {
    level = info                  # trace|debug|info|warn|error
  }
}
```

- 默认路径：`~/.devpulse/config.conf`
- 覆盖：`--config <path>`（也可用 `DEVPULSE_CONFIG` 环境变量，供不便带参数的场景）
- 文件不存在时使用内置默认值，不报错；文件存在但格式错误时**报错退出**，不静默回退
- `server.host` 只接受回环地址，其它值在加载阶段拒绝

### 8. 优先级

只有三层，没有多目录层叠：

```text
内置默认  <  配置文件（--config 或默认路径）  <  命令行参数
```

命令行参数只覆盖同名项（如 `--port`），其余仍来自配置文件。

加载器记录每个最终值的来源，`devpulse doctor` 输出：

```text
Effective configuration
  server.port      12580   (default)
  log.level        debug   (~/.devpulse/config.conf)
  run.directory    .       (default)
```

### 9. 密钥

密钥不进配置文件：直接用环境变量（如 `JIRA_API_TOKEN`）。配置文件可能是仓库里被提交的东西，密钥放进去迟早会泄漏。

如果后续出现"必须落盘"的密钥，再单独引入 `secrets.conf`（0600）——本次不做。

### 10. 服务端接口影响

客户端不解析 workflow 文件，只负责定位与读取：

```text
POST /api/runs
{ "workflow": "build", "payload": "<文件原文>", "directory": "/path/to/project" }
```

服务端负责解析、校验、建图、指纹、执行，"配置语义只有一份"落在服务端。`review` 复用同一份 payload，走 `POST /api/plan`。

### 11. 日志

删除 `logback.xml`，改为按 `devpulse.log.level` 在代码里配置 appender：

- 少一种格式
- 消掉 native 下 Joran 的反射依赖（native 推迟时记录的两个阻碍之一）

测试仍用 `logback-test.xml` 静音，只在测试 classpath 上。

### 12. 迁移清单

| 位置 | 改动 |
|---|---|
| `PulseConfiguration` | 解析单 workflow 文件；顶层校验 |
| `PulsePlan` | 去掉 target 解析，计划即整个文件 |
| `RunCommand` / `ReviewCommand` | 参数 `[TARGET]` → `[WORKFLOW]`；寻址规则 |
| `InitCommand` | 生成 `.workflow/<id>.yaml` |
| `ServerCommand` | 增加 `--config`；端口/目录默认值来自配置 |
| `DoctorCommand` | 增加"生效配置 + 来源"一节 |
| `ServerSettings` | 由配置构造 |
| 测试 | `PulseConfigurationTest`、`RunCommandJsonTest`、`ReviewCommandJsonTest`、`RunDetailEndToEndTest` |
| 文档 | `devpulse-cli/README.md`、`pulse-commands.md`、各 architecture 文档、`product-shape.md`、`CLAUDE.md`、copilot 指令 |
| 示例与脚本 | `run.sh` 示例、`native.sh` 冒烟用例 |

### 13. run 记录的 schema 变更

`run` 表新增两列（v3 仍是未发布的基线，直接改 `V1__central-store.sql`，不新增迁移文件）：

| 列 | 类型 | 说明 |
|---|---|---|
| `workflow_id` | text | 提交时的 workflow id（文件 stem） |
| `workflow_path` | text | 提交时给出的路径；能相对项目目录表示时存相对路径 |

`plan.conf` 快照仍在，重跑以快照为准；新增两列的作用是让 UI、`status` 和日志能显示"这是哪个 workflow、文件在哪"，而不必解析快照。

建议再加一列 `workflow_hash`（payload 的 SHA-256）：文件被改过之后重跑，能判断"跑的是旧快照还是新内容"。这一条是我加的，需要你确认。

### 14. 明确不做

- 不做多格式兼容（不读 `.devpulse.yaml`、不读 TOML）
- 不做多目录配置层叠（无项目级 `devpulse.conf`）
- 不做配置热加载（改配置需重启服务端）
- 不做远程配置中心
- 不做跨文件隐式依赖
- 不把 Jira 配置并入主配置

### 15. 已确认

| # | 决定 |
|---|---|
| 1 | 默认目录 `.workflow/`；同时接受显式路径 |
| 2 | `kind: workflow` 留到阶段 2 |
| 3 | run 记录存 `workflow_id` 与 `workflow_path` |

---

## English

### 1. Problem

Configuration has no system today: a YAML task file at the project root, HOCON for Jira, command-line flags only for the server, and an XML logging file on the classpath. Server settings cannot be stored, three formats coexist, and changing the log level needs a rebuild.

### 2. Responsibility boundary

| | workflow file | application configuration |
|---|---|---|
| Written by | the user, for workflows | the user, for this machine |
| Consumed by | the server, as a **payload** | the client itself |
| In git | yes | no |
| Moves with the project | yes | no |

A workflow file therefore contains **no** server or machine settings, and the application configuration contains **no** task definitions.

### 3. Files and directories

```text
project/
├── .workflow/              # one file per workflow
└── .devpulse/              # runtime state (generated, gitignored)

~/.devpulse/
├── config.conf             # the single configuration file (HOCON)
└── server.json             # server runtime state
```

**One configuration file per tool**, at a fixed default location, overridden by `--config <path>`. No directory scanning and no project/user layering. Jira already follows this pattern, and the server now matches it.

Inputs are `workflows/` and `config.conf`; `.devpulse/` is output.

### 4. Workflow file

The file name is the identifier: `.workflow/build.yaml` has id `build`.

| Field | Required | Meaning |
|---|---|---|
| `name` | no | display name only; never used for addressing |
| `env` | no | merged into every task; task-level `env` wins |
| `tasks` | yes | non-empty list |
| `tasks[].id` | yes | unique in the file, `^[a-z][a-z0-9_-]*$` |
| `tasks[].kind` | yes | executor kind; only `bash` at first |
| `tasks[].needs` | no | dependency ids |
| `tasks[].spec` | yes | private data for that kind |

Validation runs on the server at submission time, before any run record exists: a single YAML document, unknown top-level fields rejected, duplicate ids, dangling `needs` and cycles reported with location, empty files rejected.

One file is one DAG. Cross-file dependencies do not exist.

### 5. Composition (phase 2)

Reuse is expressed as a task kind rather than a global registry:

```yaml
  - id: lint
    kind: workflow
    spec:
      file: lint.yaml      # resolved relative to the referencing file
```

Paths resolve relative to the referencing file, cycles are detected at submission time, and referenced files follow the same rules. The first version rejects `kind: workflow` explicitly.

### 6. Command surface

| Command | Argument | Behaviour |
|---|---|---|
| `devpulse run build` | workflow id | finds `.workflow/build.yaml` |
| `devpulse run .workflow/build.yaml` | path | uses that file |
| `devpulse run` | none | lists workflows, exits 2 |
| `devpulse review build` | as `run` | plans without executing |
| `devpulse init build` | workflow id | writes a template |
| `devpulse server start --config ./dev.conf` | config path | starts with that configuration |

An argument containing a path separator or ending in `.yaml` is a path; otherwise it is an id under `.workflow/`. A miss reports the candidates. `.devpulse.yaml` is no longer read, and `run` loses the target concept.

### 7. Application configuration

One HOCON file; every key has a default:

```hocon
devpulse {
  workflows { dir = ".workflow" }

  server {
    host = 127.0.0.1
    port = 12580
  }

  run { directory = "." }

  log { level = info }
}
```

The default path is `~/.devpulse/config.conf`, overridden by `--config <path>` (or `DEVPULSE_CONFIG`). A missing file means defaults; a malformed file is an error rather than a silent fallback. `server.host` accepts loopback only.

### 8. Precedence

Three layers, no directory stacking:

```text
built-in defaults  <  configuration file  <  command-line options
```

Command-line options override only their own keys. The loader records where each final value came from, and `devpulse doctor` prints it:

```text
Effective configuration
  server.port      12580   (default)
  log.level        debug   (~/.devpulse/config.conf)
  run.directory    .       (default)
```

### 9. Secrets

Secrets stay in environment variables (`JIRA_API_TOKEN`); the configuration file may be committed, so secrets do not belong in it. A dedicated `secrets.conf` (0600) is out of scope for now.

### 10. Server API impact

The client locates and reads the file but does not parse it:

```text
POST /api/runs
{ "workflow": "build", "payload": "<file text>", "directory": "/path/to/project" }
```

The server parses, validates, plans, fingerprints and executes, so configuration semantics live in one place. `review` sends the same payload to `POST /api/plan`.

### 11. Logging

Delete `logback.xml` and configure appenders in code from `devpulse.log.level`: one format fewer, and no Joran reflection in a future native image. Tests keep `logback-test.xml` on the test classpath only.

### 12. Migration

`PulseConfiguration` parses a single workflow; `PulsePlan` drops target resolution; `RunCommand` and `ReviewCommand` take `[WORKFLOW]`; `InitCommand` writes `.workflow/<id>.yaml`; `ServerCommand` gains `--config`; `DoctorCommand` gains the effective-configuration section; tests and guides follow.

### 13. Run record schema change

The `run` table gains `workflow_id` and `workflow_path` (the path as given, relative to the project directory when possible). The baseline `V1__central-store.sql` is still unpublished, so it is edited in place. The `plan.conf` snapshot remains authoritative for reruns; the new columns let the UI, `status` and logs say which workflow and file a run belongs to without parsing the snapshot. A `workflow_hash` column (SHA-256 of the payload) would let a rerun distinguish a stale snapshot from newly edited content — that suggestion still needs a decision.

### 14. Out of scope

No legacy formats, no multi-directory layering, no hot reload, no remote configuration service, no implicit cross-file dependencies, and Jira configuration stays separate.

### 15. Settled

| # | Decision |
|---|---|
| 1 | Default directory `.workflow/`, explicit paths also accepted |
| 2 | `kind: workflow` waits for phase 2 |
| 3 | A run record stores `workflow_id` and `workflow_path` |
