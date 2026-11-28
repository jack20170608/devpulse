# devpulse-plugin 架构设计 / devpulse-plugin architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md)

### 这个模块是什么

`devpulse-plugin` 是一个 **POM 聚合模块**，不产出任何代码，只负责把执行插件收在同一个父 POM 下，与 `devpulse-tools` 聚合独立工具的形态一致。当前包含一个插件：

```
devpulse-plugin/          聚合（packaging=pom，artifactId=devpulse-plugin）
  bash/                   Bash 执行插件（artifactId=bash）
```

在根 reactor 里，它排 `devpulse-core` 之后、`devpulse-execution` 之前：插件依赖 core，执行层依赖插件。

### 插件必须遵守的三条规则

1. **只依赖 `devpulse-core`**（必要时额外依赖 `devpulse-storage`）。聚合 POM 已经声明了 core，插件模块继承即可。
2. **插件之间不互相依赖。** 与 `devpulse-tools` 的"工具不可互相依赖"是同一条规则；共享代码放进 core 或独立库，不放进另一个插件。
3. **不依赖 `devpulse-execution` 或任何 CLI 模块。** 依赖方向必须保持 `execution → plugin`，插件才能脱离主程序单独测试。

### 插件与执行层的接缝

执行层需要记录运行，记录实现却在 `devpulse-execution`。为避免 `execution → plugin → execution` 的环，插件对上层只暴露**最小接口**：Bash 插件定义 `TaskHistory`（`id`、`directory`、`taskId`、`started`、`openLog`、`processExited`），由执行层的 `RunHistory.Writer` 实现。

这不是完整的插件协议，只是一个依赖倒置的接缝。完整的执行器 SPI（`TaskBackend` 注册表、`kind`/`spec` 分发）尚未引入，时机与路线见 [core + plugin 演进预研](../research/core-plugin-evolution.md)：**等第二个执行后端出现再做**，现在加接口只会被唯一实现绑架。

### 装配方式

当前是**编译期静态装配**：`devpulse-execution` 在代码里直接构造 `BashTaskDefinition`，`devpulse-cli` 的依赖图里包含 `bash`。没有运行期类加载，也没有 `ServiceLoader`；这与产品决策"不做运行期热插拔插件"一致（运行期加载与 GraalVM native 冲突）。

### 新增一个插件时需要改什么

| 步骤 | 内容 |
|------|------|
| 1 | 在 `devpulse-plugin/` 下新建子模块，父 POM 指向 `devpulse-plugin`，artifactId 用不带前缀的名字（参考 `bash`） |
| 2 | 把模块加进 `devpulse-plugin/pom.xml` 的 `<modules>` 和 `devpulse-bom` |
| 3 | 定义自己的配置片段与 `DagTask`/`DagDaemon` 绑定，不读取 Pulse YAML |
| 4 | 若需要写运行记录，实现或复用 `TaskHistory` 这样的最小接口，而不是直接依赖 `devpulse-execution` |
| 5 | 在 `docs/devpulse-plugin/<name>/architecture.md` 补一篇双语架构说明 |

## English

[Architecture index](../architecture.md)

### What this module is

`devpulse-plugin` is a **POM aggregation module**. It produces no code; it only groups execution plugins under one parent POM, mirroring how `devpulse-tools` aggregates independent tools. It currently holds one plugin:

```
devpulse-plugin/          aggregation (packaging=pom, artifactId=devpulse-plugin)
  bash/                   Bash execution plugin (artifactId=bash)
```

In the root reactor it sits after `devpulse-core` and before `devpulse-execution`: plugins depend on core, and the execution layer depends on plugins.

### Three rules every plugin follows

1. **Depend on `devpulse-core` only** (plus `devpulse-storage` when genuinely needed). The aggregation POM already declares core, so plugins inherit it.
2. **Plugins never depend on each other.** This is the same rule as "tools must not depend on one another" in `devpulse-tools`; shared code goes into core or a standalone library, never into another plugin.
3. **Never depend on `devpulse-execution` or any CLI module.** The direction must stay `execution → plugin`, so a plugin can be tested without the host.

### The seam between plugins and the execution layer

The execution layer records runs, but the record implementation lives in `devpulse-execution`. To avoid an `execution → plugin → execution` cycle, a plugin exposes only a **minimal interface** upwards: the Bash plugin defines `TaskHistory` (`id`, `directory`, `taskId`, `started`, `openLog`, `processExited`), implemented by `RunHistory.Writer` in the execution layer.

That is not a full plugin protocol, only a dependency-inversion seam. The full executor SPI (`TaskBackend` registry, `kind`/`spec` dispatch) is deliberately not introduced yet; the timing and options are in [evolving core into a plugin host](../research/core-plugin-evolution.md): **wait for a second execution backend**, because an interface introduced for a single implementation is captured by it.

### How plugins are wired

Wiring is **compile-time and static**: `devpulse-execution` constructs `BashTaskDefinition` directly, and `devpulse-cli` lists `bash` in its dependency graph. There is no runtime class loading and no `ServiceLoader`, consistent with the product decision against runtime hot-plugging (runtime loading conflicts with GraalVM native).

### Adding a plugin

| Step | Action |
|------|--------|
| 1 | Add a submodule under `devpulse-plugin/`, parent `devpulse-plugin`, artifactId without a prefix (see `bash`) |
| 2 | Register it in `devpulse-plugin/pom.xml` `<modules>` and in `devpulse-bom` |
| 3 | Define its own configuration fragment and `DagTask`/`DagDaemon` binding; never read Pulse YAML |
| 4 | If it must record runs, implement or reuse a minimal seam such as `TaskHistory` instead of depending on `devpulse-execution` |
| 5 | Add a bilingual architecture note at `docs/devpulse-plugin/<name>/architecture.md` |
