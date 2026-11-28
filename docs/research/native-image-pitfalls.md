# GraalVM 原生镜像踩坑清单 / Native image pitfalls

[中文](#中文) | [English](#english)

## 中文

### 1. 这份文档是什么

把 devpulse 打包成 GraalVM 原生可执行文件时踩过的坑、验证过的结论，以及恢复这项工作时该从哪里开始。它不是 native-image 的使用教程，只记录**会让人返工的地方**。

**当前状态：原生镜像已推迟。** 产物只发 JVM JAR，理由是产品形态还在变，反射元数据与构建参数会跟着持续返工（见 [产品形态决策 #10](../design/product-shape.md)）。`native.sh` 保留在仓库里，但不在发布路径上，`--verify` 目前不会通过；脚本头部已经写明 `DEFERRED` 以及恢复条件。

推迟不等于结论作废：下面每一条都是真金白银试出来的，恢复时按第 10 节从上往下走即可。

### 2. 核心认知：编译成功 ≠ 能跑

native-image 是 **AOT 编译 + 静态可达性分析**。编译器看不见的调用路径——反射、资源读取、`ServiceLoader`、动态代理、反序列化——不会被链接进镜像。结果是：**构建 SUCCESS，运行到那条路径才炸**。

因此原生产物的门禁不是"编译通过"，而是**覆盖主要命令的冒烟测试**。上一轮 `native.sh --verify` 的 11 项检查就是这个角色：版本、帮助、流水线执行、`status`、`review`、长驻进程生命周期、`stop`，以及未知目标（`12`）和任务失败（`30`）两个分类退出码。

这条纪律建议保留：**没有冒烟测试的 native 构建等于没有验证。**

### 3. 元数据：五类，缺一类炸一类

| 类型 | 触发场景 | 典型症状 |
|------|----------|----------|
| `reflection` | `Class.forName`、注解驱动、ORM 映射、日志框架配置 | `ClassNotFoundException` / `NoSuchMethodException` |
| `resources` | `getResourceAsStream`、配置文件、SQL 迁移 | 流返回 `null`，报的是下游 NPE 而不是"资源缺失" |
| `proxy` | `SqlObject`、`Proxy.newProxyInstance` | `IllegalArgumentException` |
| `jni` | 本地库、native transport、嵌入式数据库 | `UnsatisfiedLinkError` |
| `serialization` | `ObjectInputStream`、部分缓存框架 | `ClassNotFoundException` |

本项目的元数据在 `devpulse-cli/src/main/resources/META-INF/native-image/` 下，由 GraalVM agent 录制。录制有两个固有局限：

1. **只覆盖录到的那条路径。** 新增命令、新增 record、新增配置分支，都要重跑一遍录制，否则编译照样通过、运行到该分支才失败。这也是推迟 native 的直接原因。
2. **会混入可选依赖。** 当前元数据里能看到 `com.ibm.icu.text.Collator` 和 `groovy.lang.Closure`——它们来自 H2 的可选路径，并非运行必需。恢复时要逐个确认，删掉不需要的，否则白占体积、白增失败面。

### 4. 本项目逐个踩过的坑

**logback（最新、最硬的一个）。** 加日志之后，服务端在 native 下启动即 `ClassNotFoundException`：logback 通过反射加载 `DefaultJoranConfigurator` 以及 XML 里声明的 appender，这些类全部需要反射元数据。同时有一处构建期初始化要求——logback 把 logger 缓存、级别表、context 放在静态字段里，而 Netty 在构建期初始化的 logger 会捕获这些实例，所以必须把整个 logback 包在构建期初始化：

```bash
native-image -jar devpulse-cli/target/devpulse.jar -o devpulse-native --no-fallback \
    --initialize-at-build-time=ch.qos.logback
```

**`native-image.properties` 在 `-jar` 模式下不生效。** 直觉做法是把构建参数写进 jar 内的 `META-INF/native-image/.../native-image.properties`，但用 `-jar` 调用时该文件不会被读取，参数必须写在命令行上。这个坑会浪费很多次"改了配置但没生效"的调试。

**资源要用 `glob` 登记，`pattern` 不生效。** 数据库迁移 SQL 通过 `getResourceAsStream` 读取，元数据里漏登记时表现是流为 `null`，排查方向容易被带偏。安全写法是宽一点的 glob：

```json
{ "glob": "**/*.sql" },
{ "glob": "**/*.conf" }
```

**反射式序列化不如手写。** 服务端状态文件刻意手写 JSON，而不是用 Gson 的 record 支持——后者在 native 下需要额外注册。`StatusJson`、`PulseJson`、`DoctorJson` 同样是显式拼装。这个决定值得沿用：**能用显式代码表达的序列化，不要交给反射。**

**picocli 依赖注解反射。** 每个 `@Command`/`@Option` 都是反射读取。agent 录制能解决，但更稳的是加 `picocli-codegen` 注解处理器，在编译期生成反射配置，新增子命令自动覆盖，不依赖"记得重录"。

**JDBI 与 H2。** `SqlObject` 走动态代理，`RowMapper` 与 record 构造走反射；JDBC 驱动通过 `META-INF/services/java.sql.Driver` 发现。三者都要在元数据里体现，缺一处就是运行期异常。

### 5. 构建期初始化：最阴的一类 bug

`--initialize-at-build-time` 让静态初始化**在构建机上执行**并把结果烧进镜像。轻则行为诡异，重则静默错误：

- 静态字段缓存了路径 → 冻结成构建机的路径
- 缓存了随机种子、时间、时区 → 所有实例共用同一个值
- 静态块读环境变量或网络 → 构建成功，运行拿到构建环境的值

默认是运行期初始化，**不要为了"让构建通过"而无脑全量 build-time init**。真需要时按包精确标注（logback 那条就是按包标注的正常用法）。

反过来也会遇到库**要求**构建期初始化，报错形如 `Classes that should be initialized at run time got initialized during image building`，照着错误里给出的类逐个加。

### 6. 平台与工具链

- **不能交叉编译。** Linux 上出不了 Windows 可执行文件，反之亦然。要发几个平台就几个构建矩阵，各自跑冒烟。
- **Linux 需要 zlib 开发库。** 链接阶段 `-lz` 找不到会失败，需要 `zlib1g-dev`（Debian/Ubuntu）。
- **Windows 需要 MSVC Build Tools 和 Windows SDK**，且必须在 Windows 侧的 shell 里构建，WSL 里构建出来的是 Linux 二进制。
- **glibc 与 musl 不通用。** 在 Alpine 上构建的产物拿到 Ubuntu 上跑不了；要么固定构建镜像，要么走 `--static --libc=musl`。
- **元数据与平台相关。** 上一轮在 Linux x64 上录制；换平台构建前应在目标平台重新录制，否则平台相关的代码路径（文件系统、控制台、native transport）会缺配置。
- **包装脚本要重写。** `devpulse-dist/src/bin/` 下是 bash 启动脚本，Windows 用户拿到原生 exe 后无法复用，需要 `.cmd` 或直接运行 exe。

### 7. 运行时语义差异

- **没有 JIT。** 短命令收益明显，长驻服务恰好相反：`devpulse-server` 是常驻进程，稳态吞吐在 native 下通常低于 JVM。这也是推迟 native 时提到"形态未定"的实际含义之一——先发 JAR，服务端的性能特征更可预期。
- **`java.home` 为空**，`java.class.path` 不可靠。任何拼 `JAVA_HOME` 或自省 classpath 的代码都要改。
- **默认字符集是 UTF-8**，不受系统 locale 影响。读 GBK 文件会乱码，且需要 `-H:+AddAllCharsets` 才会把非默认字符集编进镜像。
- **虚拟线程**：GraalVM 21+ 支持，但诊断能力（线程 dump 等）不完整。本项目 DAG 调度用虚拟线程，排障时要意识到这一点。
- **栈更小**，深递归或大栈帧会 `StackOverflowError`。
- **不支持**：JMX、agent attach、运行期字节码生成（CGLIB/ByteBuddy 增强）、JFR 大部分事件。
- **HTTPS 证书**：镜像内没有 JVM 的证书链兜底，精简容器里常缺 `/etc/ssl/certs`。

### 8. 体积与构建成本

上一轮 CI 全绿时的实测（已推迟，此处仅作参考量级）：

| 指标 | 实测 |
|------|------|
| 二进制大小 | 约 69 MB |
| `--version` / `--help` 启动 | 约 120 ms（JVM JAR 约 300 ms） |
| 构建耗时 | 分钟级，CI 曾给 30 分钟超时 |
| 必需系统依赖 | `zlib1g-dev` |

构建内存是 GB 级；CI 必须缓存 Maven 与 GraalVM，否则每次从头编译。

### 9. 四条路线的取舍

| 方案 | 体积 | 启动 | 反射风险 | 适用 |
|------|------|------|----------|------|
| JVM JAR（当前） | 最小 | ~300 ms | 无 | 形态未定、需要可预期行为 |
| jlink + AppCDS | ~50 MB | ~150–200 ms | 无 | 启动延迟有痛感但不想承担 native 风险 |
| jpackage | ~200 MB | ~300 ms | 无 | 面向非技术用户的安装包 |
| native-image | ~69 MB | ~120 ms | 高 | 形态稳定、启动延迟是核心体验 |

判断标准：**启动延迟是不是用户可感知的痛点**。如果是构建流水线里被反复调用的工具，`jlink + AppCDS` 用一小部分风险拿到大部分收益；如果形态还会变，先把 JAR 做对。

### 10. 恢复 native 的检查清单

按顺序执行，每步都要有可验证的输出：

1. `bash native.sh` 能构建出二进制（需要 `GRAALVM_HOME` 或 PATH 上的 `native-image`）
2. 补 logback 反射元数据：`DefaultJoranConfigurator` 与 `logback.xml` 里声明的 appender 类
3. 保留 `--initialize-at-build-time=ch.qos.logback`，确认参数在命令行上而不是 `native-image.properties` 里
4. 清理元数据中的可选依赖（`groovy.lang.Closure`、`com.ibm.icu.text.Collator`），逐个验证确实不需要
5. 检查 `db/migration/*.sql` 等资源已被 glob 覆盖，且新增文件不会漏
6. 在当前平台用 GraalVM agent 重录一次，跑完整命令矩阵
7. `bash native.sh --verify` 全绿
8. 把 native job 加回 `.github/workflows/verify.yml`，并按需扩成多平台矩阵
9. 评估 `picocli-codegen` 注解处理器，减少"忘记重录"的返工
10. 在发行文档里说明原生版与 JAR 的功能差异与回退方式

### 11. 一句话总结

native-image 的坑不在"编不出来"，而在"编出来了但某条路径没编进去"。**把冒烟测试当成真正的门禁，把元数据当成需要回归的构建产物，而不是一次性配置。**

## English

### 1. What this document is

The pitfalls hit while packaging devpulse as a GraalVM native executable, the conclusions that survived, and where to restart when the work resumes. It is not a native-image tutorial; it records only **the parts that cost rework**.

**Current state: the native image is deferred.** The product ships the JVM JAR only, because the product shape is still moving and the reflection metadata and build flags would keep changing with it (see [product shape decision #10](../design/product-shape.md)). `native.sh` stays in the repository but is not on the release path, and `--verify` does not pass today; the script header states `DEFERRED` and what re-enabling requires.

Deferral does not invalidate the findings: every item below was paid for in practice. When the work resumes, start at section 10 and walk down.

### 2. Core insight: compiling is not running

native-image is **AOT compilation plus static reachability analysis**. Call paths the compiler cannot see — reflection, resource lookup, `ServiceLoader`, dynamic proxies, deserialization — are not linked into the image. The result: **the build succeeds and the binary fails on that path at run time**.

So the gate for a native artifact is not "it compiled" but **smoke tests covering the main commands**. The 11 checks in the previous `native.sh --verify` filled that role: version, help, pipeline execution, `status`, `review`, daemon lifecycle, `stop`, plus the two categorized exit codes for an unknown target (`12`) and task failure (`30`).

Keep that discipline: **a native build without smoke tests is untested.**

### 3. Metadata: five kinds, each one a failure mode

| Kind | Triggered by | Typical symptom |
|------|--------------|-----------------|
| `reflection` | `Class.forName`, annotation-driven code, ORM mapping, logging configuration | `ClassNotFoundException` / `NoSuchMethodException` |
| `resources` | `getResourceAsStream`, config files, SQL migrations | the stream is `null`, and the failure surfaces as a downstream NPE |
| `proxy` | `SqlObject`, `Proxy.newProxyInstance` | `IllegalArgumentException` |
| `jni` | native libraries, native transports, embedded databases | `UnsatisfiedLinkError` |
| `serialization` | `ObjectInputStream`, some cache frameworks | `ClassNotFoundException` |

This project's metadata lives under `devpulse-cli/src/main/resources/META-INF/native-image/` and was recorded with the GraalVM agent. Recording has two inherent limits:

1. **It covers only the paths that ran.** A new command, a new record, or a new configuration branch needs a fresh recording; otherwise the build still passes and only that branch fails at run time. This is the direct reason the native image is deferred.
2. **It pulls in optional dependencies.** The current metadata lists `com.ibm.icu.text.Collator` and `groovy.lang.Closure`, which come from H2's optional paths and are not required at run time. Prune them when resuming, or they cost size and widen the failure surface for nothing.

### 4. Lessons paid for one by one

**logback (newest and hardest).** After logging was introduced, the server failed at startup under native with `ClassNotFoundException`: logback loads `DefaultJoranConfigurator` and the appenders named in the XML reflectively, so all of those classes need reflection metadata. There is also a build-time initialization requirement — logback keeps its logger cache, level table and context in static fields, and Netty's build-time-initialized loggers capture those instances, so the whole logback package must be initialized at build time:

```bash
native-image -jar devpulse-cli/target/devpulse.jar -o devpulse-native --no-fallback \
    --initialize-at-build-time=ch.qos.logback
```

**`native-image.properties` is not read in `-jar` mode.** The intuitive move is to put the build flags into `META-INF/native-image/.../native-image.properties` inside the jar, but that file is ignored when native-image is invoked with `-jar`; the flags must be on the command line. This one burns a lot of cycles debugging "I changed the config and nothing happened".

**Register resources with `glob`, not `pattern`.** Migration SQL is read through `getResourceAsStream`; when the metadata misses it the stream is `null`, which sends debugging in the wrong direction. Widening the glob is safer:

```json
{ "glob": "**/*.sql" },
{ "glob": "**/*.conf" }
```

**Hand-written serialization beats reflective serialization.** The server state file is deliberately hand-written JSON rather than Gson's record support, which needs extra registration under native. `StatusJson`, `PulseJson` and `DoctorJson` are assembled explicitly the same way. Keep this: **if serialization can be explicit code, do not route it through reflection.**

**picocli relies on annotation reflection.** Every `@Command`/`@Option` is read reflectively. Agent recording works, but the `picocli-codegen` annotation processor is more robust: it generates the reflection configuration at compile time, so a new subcommand is covered automatically instead of depending on someone remembering to re-record.

**JDBI and H2.** `SqlObject` uses dynamic proxies, mappers and record constructors use reflection, and the JDBC driver is discovered through `META-INF/services/java.sql.Driver`. All three must appear in the metadata; miss one and it fails at run time.

### 5. Build-time initialization: the sneakiest bug class

`--initialize-at-build-time` runs static initializers **on the build machine** and bakes the results into the image. The failure modes range from odd behaviour to silently wrong values:

- a static field caching a path → frozen to the build machine's path
- a cached random seed, clock or time zone → every instance shares one value
- a static block reading the environment or the network → the build passes, and the binary uses the build environment's values

The default is run-time initialization, so **do not apply build-time init wholesale just to make the build pass**. When it is genuinely needed, scope it to a package — the logback flag is the normal, narrow use.

The opposite case also exists: a library that **requires** build-time initialization and reports `Classes that should be initialized at run time got initialized during image building`. Add the classes named in the error, one by one.

### 6. Platforms and toolchains

- **No cross-compilation.** Linux cannot produce a Windows executable or vice versa; one build matrix per target platform, each running its own smoke suite.
- **Linux needs the zlib development package.** Linking fails without `-lz`; install `zlib1g-dev` on Debian/Ubuntu.
- **Windows needs MSVC Build Tools and the Windows SDK**, and must be built from a Windows shell — a WSL build produces a Linux binary.
- **glibc and musl are not interchangeable.** An Alpine-built binary does not run on Ubuntu; either pin the build image or use `--static --libc=musl`.
- **Metadata is platform-specific.** The previous recording was made on Linux x64; re-record on the target platform, or platform-specific paths (filesystem, console, native transports) will be missing.
- **Wrapper scripts need rewriting.** `devpulse-dist/src/bin/` contains bash launchers; a Windows user with the native exe cannot reuse them and needs `.cmd` wrappers or the exe directly.

### 7. Runtime semantic differences

- **No JIT.** Short commands benefit; long-running services do not. `devpulse-server` is a daemon, and its steady-state throughput is generally lower under native than on the JVM. That is part of what "the shape is not settled" means in practice: ship the JAR and keep server performance predictable.
- **`java.home` is empty** and `java.class.path` is unreliable. Any code composing `JAVA_HOME` or introspecting the classpath must change.
- **The default charset is UTF-8**, independent of the system locale. Reading GBK files breaks, and `-H:+AddAllCharsets` is needed to bundle non-default charsets.
- **Virtual threads** work on GraalVM 21+, but diagnostics (thread dumps and friends) are incomplete. This project's DAG scheduler uses virtual threads; remember that when debugging.
- **Smaller stacks**: deep recursion or large frames can `StackOverflowError`.
- **Unsupported**: JMX, agent attach, run-time bytecode generation (CGLIB/ByteBuddy enhancement), most JFR events.
- **HTTPS certificates**: there is no JVM trust-store fallback inside the image; slim containers often lack `/etc/ssl/certs`.

### 8. Size and build cost

Measured in the previous CI-green state (deferred now; these are reference magnitudes):

| Metric | Measured |
|--------|----------|
| Binary size | ~69 MB |
| `--version` / `--help` startup | ~120 ms (JVM JAR ~300 ms) |
| Build time | minutes; CI allowed 30 minutes |
| Required system package | `zlib1g-dev` |

The build needs gigabytes of memory, and CI must cache both Maven and GraalVM or every run compiles from scratch.

### 9. Choosing among the four options

| Option | Size | Startup | Reflection risk | Fits when |
|--------|------|---------|-----------------|-----------|
| JVM JAR (current) | smallest | ~300 ms | none | the shape is unsettled and behaviour must be predictable |
| jlink + AppCDS | ~50 MB | ~150–200 ms | none | startup latency hurts but native risk is unwanted |
| jpackage | ~200 MB | ~300 ms | none | the audience needs an installer |
| native-image | ~69 MB | ~120 ms | high | the shape is stable and startup is the core experience |

The test is simple: **is startup latency a pain the user can feel?** For a tool invoked repeatedly inside a build pipeline, `jlink + AppCDS` buys most of the benefit for a fraction of the risk. While the shape is still moving, get the JAR right first.

### 10. Checklist for resuming

Run these in order; each step needs a verifiable output:

1. `bash native.sh` produces a binary (needs `GRAALVM_HOME` or `native-image` on PATH)
2. Add logback reflection metadata: `DefaultJoranConfigurator` and the appender classes named in `logback.xml`
3. Keep `--initialize-at-build-time=ch.qos.logback`, and confirm the flag is on the command line rather than in `native-image.properties`
4. Prune optional dependencies from the metadata (`groovy.lang.Closure`, `com.ibm.icu.text.Collator`) after verifying they are genuinely unused
5. Verify resources such as `db/migration/*.sql` are covered by globs and that new files cannot be missed
6. Re-record with the GraalVM agent on the current platform and run the full command matrix
7. `bash native.sh --verify` passes in full
8. Restore the native job in `.github/workflows/verify.yml`, widened to a multi-platform matrix as needed
9. Evaluate the `picocli-codegen` annotation processor to reduce "forgot to re-record" rework
10. Document the functional differences between the native build and the JAR, including how to fall back

### 11. One sentence

The trap in native-image is not "it will not compile" but "it compiled and one path was left out". **Treat smoke tests as the real gate, and treat metadata as a build artifact that needs regression coverage, not one-time configuration.**
