# core + plugin 演进预研 / Evolving core into a plugin host

[中文](#中文) | [English](#english)

## 中文

### 1. 结论先行

**现在不要做插件。** 当前的分层（`core` → `bash` → `execution` → `cli`）已经能表达"调度与执行分离"，而插件机制要解决的问题——**在不改核心模块的前提下新增一种执行后端**——还没有第二个真实实例。为一个实现做抽象，只会得到一个约束了所有未来实现的错误接口。

本文记录的是：拆分完成后，**如果真的出现第二种执行后端**，应该沿哪条路径演进、以什么标准判断时机、以及哪些做法会与现有约束（尤其是 GraalVM native）冲突。

### 2. 现状：分层已成立，扩展点还没有

本次拆分后的依赖方向：

```
devpulse-core        DAG 调度，零依赖
   ↑
devpulse-plugin/bash        BashShell / BashTaskAdapter / DaemonManager / TaskHistory
   ↑
devpulse-execution   配置解析、计划装配、RunService、RunHistory
   ↑
devpulse-cli / devpulse-server
```

扩展点目前是**硬编码在 `devpulse-execution` 里的**：YAML 的 `cmd` 字段直接构造 `BashTaskDefinition`，再由 `BashTaskAdapter` 绑定成 `DagTask`。新增一种后端（例如 JVM 内任务、Docker、SSH）需要修改 `execution`，这正是插件机制要消除的耦合。

但请注意：**已经把执行器拆成独立模块，就已经拿到了 80% 的收益**——编译期依赖方向正确、可以单独测试、可以单独替换。剩下的 20% 是"新增后端时不触碰任何现有模块"，这部分还不值得现在付成本。

### 3. 三条可选路线

| 路线 | 机制 | 优点 | 代价 | 结论 |
|------|------|------|------|------|
| A. 编译期静态插件 | `ServiceLoader` + jar 内 `META-INF/services` | 与 native 兼容、可做依赖分析、启动零成本 | 新增后端要重新打包 | **推荐** |
| B. 运行期热插拔 | `URLClassLoader` 加载外部 jar | 不用重新打包 | 与 native 冲突、类加载器隔离、版本/安全风险 | 不推荐 |
| C. 独立进程插件 | 通过进程或 HTTP 协议调用 | 语言无关、隔离彻底 | 协议演进成本高、调试困难 | 只在需要跨语言时考虑 |

路线 B 与产品决策直接冲突：native 镜像无法在运行期加载并链接新类，仓库也明确"不做运行期热插拔插件"。路线 C 的复杂度远超当前需求。因此**唯一与现有约束相容的是 A**。

### 4. 路线 A 的具体形状

#### 4.1 一个零依赖的 SPI 模块

新增 `devpulse-spi`（或直接在 `devpulse-core` 里定义接口，避免多一个模块）：

```java
public interface TaskBackend {
    String id();                       // "bash", "java", "docker"
    boolean supports(String type);     // .devpulse.yaml 里的 type
    TaskPlan plan(NodeConfig config);  // 解析自己的配置片段
    DagTask<?> task(...);
    DagDaemon<?> daemon(...);
}
```

关键约束：**SPI 接口只能依赖 `devpulse-core`**。一旦 SPI 依赖 `devpulse-storage` 或 Typesafe Config，所有实现方都被迫接受这些依赖，抽象就失败了。

#### 4.2 装配点唯一

`devpulse-execution` 里只保留一处 `ServiceLoader` 调用：

```java
ServiceLoader.load(TaskBackend.class).stream()
    .map(ServiceLoader.Provider::get)
    .collect(toMap(TaskBackend::id, identity()));
```

之后所有"按类型分派"的代码都从这张表查，不再出现 `instanceof BashTaskDefinition` 或语言枚举。Bash 成为第一个注册项，行为与今天完全一致——**这才是可验证的重构**：重构后所有现有测试必须原封不动通过。

#### 4.3 配置分发的边界

与 [产品形态](../design/product-shape.md) 第 6 节一致：**平台校验文档与图**（`version`、`parallelism`、重名、依赖存在、无环、`kind` 已注册、`spec` 是映射），**每个执行器校验自己的 `spec`**。

这个分工的理由：

- 平台不需要理解每种后端的字段，新增后端不必改平台；
- 后端拥有自己的错误信息，字段级报错不经过一层转述；
- 未知 `kind` 由注册表给出明确错误，而不是 `NullPointerException`；
- 执行指纹必须包含执行器身份（`kind`、规范化后的 `spec`、执行器版本），否则两个后端对同一节点会产生相同指纹。

#### 4.4 与 GraalVM 的兼容性

`ServiceLoader` 在 native 镜像下需要 `META-INF/services` 资源被保留。当前 `reachability-metadata.json` 里已经有资源 glob，但 native 支持推迟到产品形态稳定之后，**插件的引入不应早于 native 的恢复决策**——否则两件事会互相混淆归因。

### 5. 什么信号出现时才做

按顺序满足，缺一不做：

1. **出现第二个真实后端**，且需求明确（不是"将来可能"）。
2. 第二个后端与 Bash 共享同一个 `DagRun` 调度，而不是自带一套。
3. 能写出至少 3 个跨后端测试，证明注册表分派正确。
4. 现有测试在引入 `ServiceLoader` 后**无需修改**即可通过。

只满足第 1 条时，更便宜的做法是：**在 `devpulse-execution` 里加一层工厂方法**，先让分派逻辑集中，不引入 SPI。等第三个后端出现，再抽象接口。

### 6. 反模式清单

| 反模式 | 后果 |
|--------|------|
| 为单一实现先定义接口 | 接口形状被唯一实现绑架，第二个实现必然要改接口 |
| 让插件模块依赖 `devpulse-execution` | 依赖反向，插件无法脱离主程序测试 |
| 用字符串开关代替注册表 | 分派逻辑散落，新增后端仍要改多处 |
| 允许运行期加载外部 jar | 与 native 冲突，且引入类加载器与版本管理问题 |
| 插件各自解析 YAML | 配置语义分裂，错误信息不一致 |
| 插件版本不随主版本发布 | 二进制兼容问题；应统一用 `${project.version}` 与 BOM 管理 |

### 7. 与本次拆分的关系

本次拆分已经完成了插件化的**前置条件**：

- `devpulse-core` 零依赖，任何后端都能依赖它而不引入多余库；
- `TaskHistory` 已经演示了**依赖倒置**：执行器依赖接口，记录实现留在上层。插件化的核心技巧与此相同，只是把"一个接缝"扩展成"一张注册表"；
- `devpulse-plugin/bash` 不依赖 `devpulse-execution`，证明执行后端可以独立存在。

因此下一步不是加接口，而是**等待第二个后端**。在那之前，保持 `devpulse-execution` 是唯一的分派点，比提前抽象更有价值。

## English

### 1. Bottom line

**Do not build a plugin system yet.** The current layering (`core` → `bash` → `execution` → `cli`) already expresses "scheduling is separate from execution", and the problem a plugin mechanism solves — **adding an execution backend without touching the core modules** — has no second real instance. Abstracting for one implementation yields an interface shaped by that implementation and constrains every future one.

This document records how to evolve **if a second execution backend actually appears**, how to judge the moment, and which options conflict with existing constraints (especially GraalVM native).

### 2. Current state: layering done, extension point missing

The dependency direction after this split:

```
devpulse-core        DAG scheduling, zero dependencies
   ↑
devpulse-plugin/bash        BashShell / BashTaskAdapter / DaemonManager / TaskHistory
   ↑
devpulse-execution   configuration, plan assembly, RunService, RunHistory
   ↑
devpulse-cli / devpulse-server
```

The extension point is currently **hard-coded inside `devpulse-execution`**: the YAML `cmd` field builds a `BashTaskDefinition`, which `BashTaskAdapter` binds into a `DagTask`. A new backend (in-JVM tasks, Docker, SSH) would require editing `execution`, which is exactly the coupling a plugin removes.

But note: **splitting the executor into its own module already captured 80% of the benefit** — the compile-time dependency direction is right, the backend is testable in isolation, and it can be replaced on its own. The remaining 20% is "no existing module changes when a backend is added", and that is not worth paying for now.

### 3. Three options

| Option | Mechanism | Upside | Cost | Verdict |
|--------|-----------|--------|------|---------|
| A. Compile-time static plugin | `ServiceLoader` + `META-INF/services` in the jar | Native-compatible, analysable, zero startup cost | New backend means a rebuild | **Recommended** |
| B. Runtime hot-plug | `URLClassLoader` over external jars | No rebuild | Conflicts with native, classloader isolation, version and security risk | Not recommended |
| C. Out-of-process plugin | Process or HTTP protocol | Language-agnostic, hard isolation | Protocol evolution cost, painful debugging | Only for cross-language needs |

Option B directly contradicts the product decision: a native image cannot load and link new classes at run time, and the repository explicitly rules out runtime hot-plug. Option C is far beyond current needs. **Only A is compatible with the existing constraints.**

### 4. The shape of option A

#### 4.1 A zero-dependency SPI

Add `devpulse-spi` (or define the interfaces inside `devpulse-core` to avoid another module):

```java
public interface TaskBackend {
    String id();                       // "bash", "java", "docker"
    boolean supports(String type);     // the type in .devpulse.yaml
    TaskPlan plan(NodeConfig config);  // parse this backend's fragment
    DagTask<?> task(...);
    DagDaemon<?> daemon(...);
}
```

Hard rule: **the SPI may depend on `devpulse-core` only.** If it depends on `devpulse-storage` or Typesafe Config, every implementer is forced to accept those dependencies and the abstraction has failed.

#### 4.2 One assembly point

`devpulse-execution` keeps exactly one `ServiceLoader` call:

```java
ServiceLoader.load(TaskBackend.class).stream()
    .map(ServiceLoader.Provider::get)
    .collect(toMap(TaskBackend::id, identity()));
```

Every dispatch goes through that table afterwards; no more `instanceof BashTaskDefinition` and no language enum. Bash becomes the first registered entry with behaviour identical to today — **that is what makes the refactor verifiable**: every existing test must pass unmodified.

#### 4.3 Where configuration lives

Consistent with [product shape](../design/product-shape.md) section 6: **the platform validates the document and the graph** (`version`, `parallelism`, duplicate names, existing prerequisites, acyclicity, a registered `kind`, `spec` being a mapping), and **each executor validates its own `spec`**.

Why this split:

- the platform need not understand any backend's fields, so a new backend changes no platform code;
- the backend owns its error messages, with no translation layer for field-level errors;
- an unknown `kind` produces a clear registry error instead of a `NullPointerException`;
- the execution fingerprint must include the executor identity (`kind`, normalised `spec`, executor version), or two backends would produce the same fingerprint for one node.

#### 4.4 GraalVM compatibility

`ServiceLoader` needs the `META-INF/services` resources preserved in a native image. The current `reachability-metadata.json` already carries resource globs, but native support is deferred until the product shape settles, and **plugins should not arrive before the native decision is made** — otherwise the two concerns get mixed up when attributing failures.

### 5. Signals that it is time

All of these, in order:

1. **A second real backend exists** with a concrete requirement (not "eventually").
2. That backend shares the same `DagRun` scheduler instead of bringing its own.
3. At least three cross-backend tests can prove the registry dispatches correctly.
4. Existing tests pass **unmodified** after `ServiceLoader` is introduced.

If only the first condition holds, a cheaper move is a **factory method inside `devpulse-execution`** that centralises dispatch without an SPI. Abstract the interface once a third backend appears.

### 6. Anti-patterns

| Anti-pattern | Consequence |
|--------------|-------------|
| Defining the interface for a single implementation | The shape is captured by that implementation; the second one forces a redesign |
| Letting plugin modules depend on `devpulse-execution` | Dependency inversion; plugins cannot be tested without the host |
| String switches instead of a registry | Dispatch logic scatters; adding a backend still touches many places |
| Loading external jars at run time | Conflicts with native and drags in classloader and version management |
| Each plugin parsing YAML itself | Configuration semantics split, error messages diverge |
| Plugin versions drifting from the host | Binary compatibility issues; use `${project.version}` and the BOM |

### 7. Relationship to this split

This split already delivered the **preconditions** for a plugin system:

- `devpulse-core` is zero-dependency, so any backend can depend on it without importing extra libraries;
- `TaskHistory` already demonstrates **dependency inversion**: the executor depends on an interface while the record implementation stays above. The core trick of a plugin system is the same, just scaled from one seam to a registry;
- `devpulse-plugin/bash` does not depend on `devpulse-execution`, proving an execution backend can stand alone.

So the next step is not adding interfaces; it is **waiting for a second backend**. Until then, keeping `devpulse-execution` as the single dispatch point is more valuable than premature abstraction.
