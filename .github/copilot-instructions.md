# 仓库指令 / Repository instructions

[中文](#中文) | [English](#english)

## 中文

### 文档规范

- 所有文档均需提供中英文对照，中文在前，英文在后；两种语言的内容和结构应保持一致，修改时同步更新。
- 在文档头部添加同页面语言导航：`[中文](#中文) | [English](#english)`，并使用 `## 中文` 和 `## English` 作为对应章节标题。

### 技术栈

- 开发、构建和运行环境均要求 JDK 25 或更高版本。
- 使用 Maven 管理依赖、构建与打包，在 `pom.xml` 中统一定义依赖和插件版本。
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
- 通过 `ManifestResourceTransformer` 将 `Main-Class` 设置为 `DevPulseCLI` 的全限定类名。
- 构建产物命名为 `devpulse.jar`，输出到 `target` 目录。
- 分发时，目标机器需要安装 Java 25 或更高版本。

在仓库根目录构建并运行：

```powershell
mvn clean package
java -jar target\devpulse.jar --help
```

### 测试

- 本仓库不编写单元测试。

## English

### Documentation conventions

- Provide Chinese and English versions of every document, with Chinese first and English second. Keep their content and structure consistent, and update both versions together.
- Add same-page language navigation at the top: `[中文](#中文) | [English](#english)`, using `## 中文` and `## English` as the corresponding section headings.

### Technology stack

- Require JDK 25 or later for development, builds, and runtime.
- Use Maven to manage dependencies, builds, and packaging. Define dependency and plugin versions in `pom.xml`.
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
- Use `ManifestResourceTransformer` to set `Main-Class` to the fully qualified name of `DevPulseCLI`.
- Name the build artifact `devpulse.jar` and output it to `target`.
- Distribution requires Java 25 or later on the target machine.

Build and run from the repository root:

```powershell
mvn clean package
java -jar target\devpulse.jar --help
```

### Testing

- Do not write unit tests for this repository.
