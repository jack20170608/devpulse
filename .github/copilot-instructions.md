# 仓库指令 / Repository instructions

[中文](#中文) | [English](#english)

## 中文

### 文档规范

- 所有文档均需提供中英文对照，中文在前，英文在后；两种语言的内容和结构应保持一致，修改时同步更新。
- 在文档头部添加同页面语言导航：`[中文](#中文) | [English](#english)`，并使用 `## 中文` 和 `## English` 作为对应章节标题。
- 根目录 `readme.MD` 作为简短入口；架构按 Maven 模块拆分到 `docs/<模块路径>/architecture.md`，`docs/architecture.md` 仅作导航；使用说明放在对应源码模块根目录的 `README.md`，不放入 `src/main/java`。旧文档路径保留中英文迁移链接，避免重复维护正文。

### 技术栈

- 开发、构建和运行环境均要求 JDK 21 或更高版本。
- 使用 Maven 管理依赖、构建与打包。`devpulse-dependencies/pom.xml` 管理外部依赖版本，`devpulse-bom/pom.xml` 管理内部可消费模块版本；根 `pom.xml` 的 `pluginManagement` 管理构建插件版本。
- 项目版本只在根 `pom.xml` 的 `<revision>` 定义；根项目及子模块父版本使用 `${revision}`，内部依赖版本继续用 `${project.version}`。保留 Flatten Maven Plugin 的 `resolveCiFriendliesOnly` 设置，使安装和发布的 POM 不含未解析的 `${revision}`。
- 核心、工具聚合模块、CLI 模块、发行模块及内部 BOM 通过本地相对路径继承 `devpulse-dependencies`，无需先安装兄弟 BOM 才能解析父 POM；存在内部代码依赖的模块仍需通过根 reactor 的 `--also-make` 一起构建。不要在运行模块中声明对 BOM 的普通运行时依赖。
- 根目录为 Maven 聚合项目；`devpulse-core` 仅提供 Bash 和环境盘点共享代码。Jira 在 `devpulse-tools` 下生成可执行 JAR；Pulse 的命令与统一入口合并为 `devpulse-cli` 模块，打包成一个可执行 JAR。工具模块可以依赖共享库，但不可相互依赖；共享库不注册或加载工具命令，且不使用动态插件加载。
- 使用 picocli 构建 CLI，通过 `@Command` 注解定义命令和子命令。
- 使用 Typesafe Config（`com.typesafe:config`）解析 HOCON 配置，不引入 Jackson 进行配置映射。
- 使用原生 Java 控制台输出，可选用 jansi 提供终端颜色和进度展示。

### Java 实现规范

- 优先保证设计的清晰性和可维护性，不为向后兼容保留不必要的旧接口、命令或实现；确需破坏兼容时同步更新相关使用说明。
- 优先使用 Java 21 中已正式发布的语言特性，在适合的场景使用 `record` 表达不可变配置和环境事实。
- 源码兼容基线是 Java 21，由根 POM 的 `release=21` 强制。禁止使用 Java 22 及以上的语法或 API，包括未命名变量 `_`、`Stream.gather`、`ScopedValue`、ClassFile API；这类代码会让 `release=21` 构建失败。工具链可以用 JDK 25 构建，但产物和源码必须停留在 Java 21。
- 当能够简化封闭状态集合的处理时，使用 sealed 类型、模式匹配和 switch 表达式。
- 使用文本块维护多行 AI 提示词模板。
- 默认不启用预览特性；确需使用时，记录原因及构建、运行要求，包括 `--enable-preview`。
- 优先保证简洁和可维护性，不为使用新语法而引入不必要的抽象。

### 并发与资源管理

- 优先使用虚拟线程处理阻塞的进程等待与日志读取；外部进程的并发数量需独立于线程数量限制。
- 使用 try-with-resources 管理进程输出流及其他可关闭资源。

### Maven 构建与分发

- 设置 UTF-8 源码编码，并将 Maven Compiler Plugin 的 `release` 配置为 `21`，保证产物可在 Java 21 上运行。
- 使用 Maven Shade Plugin 在 `package` 阶段生成包含运行依赖的可执行 JAR。
- 每个模块通过 `ManifestResourceTransformer` 设置自身的 `Main-Class`。
- CLI 产物为 `devpulse-cli/target/devpulse.jar`，独立工具产物在 `devpulse-tools/jira/target/devpulse-jira.jar`（Pulse 命令已并入 `devpulse.jar`）；`devpulse-dist` 打包 `devpulse` 与 Jira。
- 分发时，目标机器需要安装 Java 21 或更高版本。

在仓库根目录构建并运行：

```powershell
mvn clean package
java -jar devpulse-cli\target\devpulse.jar --help
java -jar devpulse-cli\target\devpulse.jar --help
java -jar devpulse-tools\jira\target\devpulse-jira.jar --help
```

在 Bash（Windows 使用 Git Bash）中优先通过根目录 `build.sh` 构建：`bash build.sh [all|pulse|jira|devpulse|dist] [--clean] [--quiet]`。`run.sh` 和 `../integration-test` 复用此脚本，不另写 Maven 模块选择逻辑。

### 测试

- 测试是可信度门禁的一部分，不是可选项。现状：`core`、`storage`、`pulse`、`jira` 有测试，`common`、`devpulse` 尚无。
- 采用 **test-on-touch**：修改哪个模块，就为该次改动补最小测试；不要求为全仓补齐测试。优先覆盖纯函数、边界条件和错误路径，而不是输出字符串。
- 控制测试体积：单个测试文件不超过 150 行；大段期望输出放到 `src/test/resources` 下的黄金文件，不要内联为 Java 文本块。
- 禁止快照膨胀：不要新增「用一个巨大文本块断言整个输出」的测试，改用黄金文件用例。
- 只允许增加或加强测试；不允许删除测试、放宽断言、或新增 `@Disabled`/`assumeTrue` 让构建变绿。确有必要跳过时，必须在提交说明中写明原因。

### 提交权限

- 代理**可以**执行 `git add` 与 `git commit`，但**每一次执行前都必须取得使用者确认**，不得先提交再报告。
- 请求确认时需一次给全：要暂存/提交的文件清单、提交信息草稿、已通过的验证（如 `bash verify.sh`、原生冒烟）。
- 未获确认时改动只留在工作区；不得自行决定提交范围或提交信息，也不得把未确认的改动夹带进已确认的提交。
- 使用者说"提交"时，含义是**提交 + 推送**：提交成功后立刻把当前分支推到**所有 remote**（当前为 `origin` 与 `github`），并逐个报告结果；推送失败要如实说明，不得声称已推送。
- 不得用 `git commit --amend`、`git reset`、`git rebase` 等命令改写历史，除非使用者明确要求。

### 完成定义

- 任何代码改动在视为完成前，必须运行 `bash verify.sh` 并通过。该脚本执行格式化检查、全量编译、全部测试和静态分析，是唯一的机器门禁入口。
- 修改编译器设置（如 `release`）或升级依赖版本后，必须运行 `bash verify.sh --clean`。Maven 增量编译不会因为配置变化而重编源文件内容未变的模块，会留下旧字节码并让门禁假绿。
- 格式化由 Spotless（google-java-format，AOSP 风格 4 空格）在 `verify` 阶段强制。需要修正时运行 `mvn spotless:apply`，不要手工调整缩进或换行。
- 静态分析由 SpotBugs 报告。不要为了让报告变干净而抑制警告；确需抑制时，使用带原因注释的 `@SuppressFBWarnings`。
- SpotBugs 用 `[ERROR]` 前缀打印的条目只是报告内容，不代表构建失败；判断门禁结果只看 `verify.sh` 的退出码和末尾的 `BUILD SUCCESS`。

## English

### Documentation conventions

- Provide Chinese and English versions of every document, with Chinese first and English second. Keep their content and structure consistent, and update both versions together.
- Add same-page language navigation at the top: `[中文](#中文) | [English](#english)`, using `## 中文` and `## English` as the corresponding section headings.
- Keep root `readme.MD` as a short entry point. Split architecture by Maven module into `docs/<module path>/architecture.md`, with `docs/architecture.md` serving only as an index. Put usage guides in each source module root as `README.md`, not under `src/main/java`. Keep bilingual links at former document paths instead of maintaining duplicate content.

### Technology stack

- Require JDK 21 or later for development, builds, and runtime.
- Use Maven for dependencies, builds, and packaging. Manage external dependency versions in `devpulse-dependencies/pom.xml`, consumable internal module versions in `devpulse-bom/pom.xml`, and build plugin versions in the root `pom.xml`'s `pluginManagement`.
- Define the project version only in the root `pom.xml`'s `<revision>`; use `${revision}` for root and child parent versions, while keeping `${project.version}` for internal dependencies. Retain the Flatten Maven Plugin's `resolveCiFriendliesOnly` setting so installed and deployed POMs have no unresolved `${revision}`.
- Core, the tool aggregator, the CLI module, the distribution module, and the internal BOM inherit `devpulse-dependencies` via local relative parent paths so the parent POM does not require installing a sibling BOM first. Modules with internal code dependencies must still be built through the root reactor with `--also-make`. Do not add a BOM as a regular runtime dependency.
- The root is a Maven reactor; `devpulse-core` provides only shared Bash and environment inventory code. Jira builds an executable JAR under `devpulse-tools`; Pulse's commands and the unified entry point live together in `devpulse-cli`, packaged as one executable. Tools may depend on the shared library but must not depend on one another; the shared library does not register or load tool commands, and there is no dynamic plugin loading.
- Use picocli for the CLI and its `@Command` annotations for commands and subcommands.
- Use Typesafe Config (`com.typesafe:config`) to parse HOCON configuration. Do not introduce Jackson for configuration mapping.
- Use standard Java console output, optionally with jansi for terminal colors and progress display.

### Java implementation conventions

- Prioritize clear, maintainable design over backward compatibility; do not retain unnecessary legacy interfaces, commands, or implementations. Update relevant usage documentation when making breaking changes.
- Prefer finalized language features available in Java 21. Use `record` for immutable configuration and environment facts where appropriate.
- The source compatibility baseline is Java 21, enforced by `release=21` in the root POM. Do not use Java 22 or newer syntax or APIs, including unnamed variables `_`, `Stream.gather`, `ScopedValue`, and the ClassFile API; such code fails the `release=21` build. The toolchain may build with JDK 25, but sources and artifacts stay on Java 21.
- Use sealed types, pattern matching, and switch expressions where they simplify handling closed sets of states.
- Use text blocks for multiline AI prompt templates.
- Do not enable preview features by default. If needed, document the reason and the build/runtime requirements, including `--enable-preview`.
- Prioritize simplicity and maintainability. Do not introduce unnecessary abstractions merely to use new syntax.

### Concurrency and resource management

- Prefer virtual threads for blocking process waits and log reading. Limit external-process concurrency independently of thread count.
- Use try-with-resources to manage process output streams and other closeable resources.

### Maven build and distribution

- Set UTF-8 source encoding and configure Maven Compiler Plugin with `release` set to `21`, so artifacts remain runnable on Java 21.
- Use Maven Shade Plugin during the `package` phase to produce an executable JAR containing runtime dependencies.
- Use `ManifestResourceTransformer` to set each module's own `Main-Class`.
- The unified CLI module builds `devpulse-cli/target/devpulse.jar`; the only standalone tool left is `devpulse-tools/jira/target/devpulse-jira.jar`. `devpulse-dist` packages `devpulse` and Jira individually.
- Distribution requires Java 21 or later on the target machine.

Build and run from the repository root:

```powershell
mvn clean package
java -jar devpulse-cli\target\devpulse.jar --help
java -jar devpulse-cli\target\devpulse.jar --help
java -jar devpulse-tools\jira\target\devpulse-jira.jar --help
```

In Bash (Git Bash on Windows), prefer the root `build.sh` for builds: `bash build.sh [all|pulse|jira|devpulse|dist] [--clean] [--quiet]`. `run.sh` and `../integration-test` reuse this script rather than duplicating Maven module selection.

### Testing

- Tests are part of the trust gate, not optional. Current state: `core`, `storage`, `pulse`, and `jira` have tests, while `common` and `devpulse` have none.
- Use **test-on-touch**: when you change a module, add the smallest test for that change; do not backfill tests across the whole repository. Prefer pure functions, boundary conditions, and error paths over rendered output strings.
- Keep tests small: a test file stays under 150 lines; put large expected output in golden files under `src/test/resources` instead of inline Java text blocks.
- No snapshot bloat: do not add a test that asserts an entire output with one huge text block; add a golden-file case instead.
- Only add or strengthen tests. Never delete tests, weaken assertions, or add `@Disabled`/`assumeTrue` to make the build green. If a skip is genuinely required, state the reason in the commit message.

### Commit ownership

- Agents **may** run `git add` and `git commit`, but **every invocation needs the user's confirmation first**; never commit first and report afterwards.
- The confirmation request must include, in one message: the files to stage or commit, a draft commit message, and the checks that passed (`bash verify.sh`, the native smoke suite).
- Without that confirmation the change stays in the working tree. Never decide the commit scope or message unilaterally, and never fold unconfirmed changes into an approved commit.
- When the user says "commit", it means **commit and push**: as soon as the commit succeeds, push the current branch to **every remote** (`origin` and `github` today) and report each result. A failed push is reported as failed; never claim a push that did not happen.
- Never rewrite history with `git commit --amend`, `git reset`, or `git rebase` unless the user explicitly asks.

### Definition of done

- No code change is complete until `bash verify.sh` passes. That script runs the formatting check, the full compile, all tests, and static analysis, and is the single machine gate.
- After changing compiler settings (such as `release`) or upgrading a dependency version, run `bash verify.sh --clean`. Maven incremental compilation does not recompile modules whose source content is unchanged, which leaves stale bytecode and makes the gate falsely green.
- Formatting is enforced by Spotless (google-java-format, AOSP style, 4-space indent) during the `verify` phase. Fix it with `mvn spotless:apply`; do not hand-adjust indentation or wrapping.
- Static analysis is reported by SpotBugs. Do not suppress warnings to make the report clean; when suppression is genuinely needed, use `@SuppressFBWarnings` with a comment explaining why.
- SpotBugs lines prefixed with `[ERROR]` are report content, not build failures. Judge the gate only by the `verify.sh` exit code and the trailing `BUILD SUCCESS`.
