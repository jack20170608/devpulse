# 仓库指令 / Repository instructions

[中文](#中文) | [English](#english)

## 中文

### 文档规范

- 所有文档均需提供中英文对照，中文在前，英文在后；两种语言的内容和结构应保持一致，修改时同步更新。
- 在文档头部添加同页面语言导航：`[中文](#中文) | [English](#english)`，并使用 `## 中文` 和 `## English` 作为对应章节标题。
- 根目录 `readme.MD` 作为简短入口；架构按 Maven 模块拆分到 `docs/<模块路径>/architecture.md`，`docs/architecture.md` 仅作导航；使用说明放在对应源码模块根目录的 `README.md`，不放入 `src/main/java`。旧文档路径保留中英文迁移链接，避免重复维护正文。

### 技术栈

- 开发、构建和运行环境均要求 JDK 25 或更高版本。
- 使用 Maven 管理依赖、构建与打包。`devpulse-dependencies/pom.xml` 管理外部依赖版本，`devpulse-bom/pom.xml` 管理内部可消费模块版本；根 `pom.xml` 的 `pluginManagement` 管理构建插件版本。
- 项目版本只在根 `pom.xml` 的 `<revision>` 定义；根项目及子模块父版本使用 `${revision}`，内部依赖版本继续用 `${project.version}`。保留 Flatten Maven Plugin 的 `resolveCiFriendliesOnly` 设置，使安装和发布的 POM 不含未解析的 `${revision}`。
- 核心、插件聚合模块、兼容入口、发行模块及内部 BOM 通过本地相对路径继承 `devpulse-dependencies`，无需先安装兄弟 BOM 才能解析父 POM；存在内部代码依赖的模块仍需通过根 reactor 的 `--also-make` 一起构建。不要在运行模块中声明对 BOM 的普通运行时依赖。
- 根目录为 Maven 聚合项目；`devpulse-core` 仅提供 Bash 和环境盘点共享代码。Pulse、Gather、Jira 在 `devpulse-plugins` 下各自生成可执行 JAR；工具模块可以依赖共享库，但不可相互依赖。`devpulse-cli` 是组合式兼容入口；共享库不注册或加载工具命令，且不使用动态插件加载。
- 使用 picocli 构建 CLI，通过 `@Command` 注解定义命令和子命令。
- 使用 Typesafe Config（`com.typesafe:config`）解析 HOCON 配置，不引入 Jackson 进行配置映射。
- 使用原生 Java 控制台输出，可选用 jansi 提供终端颜色和进度展示。

### Java 实现规范

- 优先使用 Java 25 中已正式发布的语言特性，在适合的场景使用 `record` 表达不可变配置和环境事实。
- 当能够简化封闭状态集合的处理时，使用 sealed 类型、模式匹配和 switch 表达式。
- 使用文本块维护多行 AI 提示词模板。
- 默认不启用预览特性；确需使用时，记录原因及构建、运行要求，包括 `--enable-preview`。
- 优先保证简洁和可维护性，不为使用新语法而引入不必要的抽象。

### 并发与资源管理

- 优先使用虚拟线程处理阻塞的进程等待与日志读取；外部进程的并发数量需独立于线程数量限制。
- 使用 try-with-resources 管理进程输出流及其他可关闭资源。

### Maven 构建与分发

- 设置 UTF-8 源码编码，并将 Maven Compiler Plugin 的 `release` 配置为 `25`，保证产物可在 Java 25 上运行。
- 使用 Maven Shade Plugin 在 `package` 阶段生成包含运行依赖的可执行 JAR。
- 每个模块通过 `ManifestResourceTransformer` 设置自身的 `Main-Class`。
- 组合式兼容入口为 `devpulse-cli/target/devpulse.jar`，独立工具产物在 `devpulse-plugins/{pulse,gather,jira}/target/devpulse-{pulse,gather,jira}.jar`；`devpulse-dist` 按工具打包。
- 分发时，目标机器需要安装 Java 25 或更高版本。

在仓库根目录构建并运行：

```powershell
mvn clean package
java -jar devpulse-cli\target\devpulse.jar --help
java -jar devpulse-plugins\pulse\target\devpulse-pulse.jar --help
java -jar devpulse-plugins\jira\target\devpulse-jira.jar --help
```

在 Bash（Windows 使用 Git Bash）中优先通过根目录 `build.sh` 构建：`bash build.sh [all|pulse|gather|jira|cli|dist] [--clean] [--quiet]`。`run.sh` 和 `../integration-test` 复用此脚本，不另写 Maven 模块选择逻辑。

### 测试

- 本仓库不编写单元测试。

## English

### Documentation conventions

- Provide Chinese and English versions of every document, with Chinese first and English second. Keep their content and structure consistent, and update both versions together.
- Add same-page language navigation at the top: `[中文](#中文) | [English](#english)`, using `## 中文` and `## English` as the corresponding section headings.
- Keep root `readme.MD` as a short entry point. Split architecture by Maven module into `docs/<module path>/architecture.md`, with `docs/architecture.md` serving only as an index. Put usage guides in each source module root as `README.md`, not under `src/main/java`. Keep bilingual links at former document paths instead of maintaining duplicate content.

### Technology stack

- Require JDK 25 or later for development, builds, and runtime.
- Use Maven for dependencies, builds, and packaging. Manage external dependency versions in `devpulse-dependencies/pom.xml`, consumable internal module versions in `devpulse-bom/pom.xml`, and build plugin versions in the root `pom.xml`'s `pluginManagement`.
- Define the project version only in the root `pom.xml`'s `<revision>`; use `${revision}` for root and child parent versions, while keeping `${project.version}` for internal dependencies. Retain the Flatten Maven Plugin's `resolveCiFriendliesOnly` setting so installed and deployed POMs have no unresolved `${revision}`.
- Core, the plugin aggregator, the compatibility entry point, the distribution module, and the internal BOM inherit `devpulse-dependencies` via local relative parent paths so the parent POM does not require installing a sibling BOM first. Modules with internal code dependencies must still be built through the root reactor with `--also-make`. Do not add a BOM as a regular runtime dependency.
- The root is a Maven reactor; `devpulse-core` provides only shared Bash and environment inventory code. Pulse, Gather, and Jira each build an executable JAR under `devpulse-plugins`. Tools may depend on the shared library but must not depend on one another. `devpulse-cli` is the combined compatibility entry point; the shared library does not register or load tool commands, and there is no dynamic plugin loading.
- Use picocli for the CLI and its `@Command` annotations for commands and subcommands.
- Use Typesafe Config (`com.typesafe:config`) to parse HOCON configuration. Do not introduce Jackson for configuration mapping.
- Use standard Java console output, optionally with jansi for terminal colors and progress display.

### Java implementation conventions

- Prefer finalized language features available in Java 25. Use `record` for immutable configuration and environment facts where appropriate.
- Use sealed types, pattern matching, and switch expressions where they simplify handling closed sets of states.
- Use text blocks for multiline AI prompt templates.
- Do not enable preview features by default. If needed, document the reason and the build/runtime requirements, including `--enable-preview`.
- Prioritize simplicity and maintainability. Do not introduce unnecessary abstractions merely to use new syntax.

### Concurrency and resource management

- Prefer virtual threads for blocking process waits and log reading. Limit external-process concurrency independently of thread count.
- Use try-with-resources to manage process output streams and other closeable resources.

### Maven build and distribution

- Set UTF-8 source encoding and configure Maven Compiler Plugin with `release` set to `25`, so artifacts remain runnable on Java 25.
- Use Maven Shade Plugin during the `package` phase to produce an executable JAR containing runtime dependencies.
- Use `ManifestResourceTransformer` to set each module's own `Main-Class`.
- The combined compatibility entry point builds `devpulse-cli/target/devpulse.jar`; standalone tools build `devpulse-plugins/{pulse,gather,jira}/target/devpulse-{pulse,gather,jira}.jar`. `devpulse-dist` packages the tools individually.
- Distribution requires Java 25 or later on the target machine.

Build and run from the repository root:

```powershell
mvn clean package
java -jar devpulse-cli\target\devpulse.jar --help
java -jar devpulse-plugins\pulse\target\devpulse-pulse.jar --help
java -jar devpulse-plugins\jira\target\devpulse-jira.jar --help
```

In Bash (Git Bash on Windows), prefer the root `build.sh` for builds: `bash build.sh [all|pulse|gather|jira|cli|dist] [--clean] [--quiet]`. `run.sh` and `../integration-test` reuse this script rather than duplicating Maven module selection.

### Testing

- Do not write unit tests for this repository.
