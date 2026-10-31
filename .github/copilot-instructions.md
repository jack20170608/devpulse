# Repository instructions

## Technology stack

- Require JDK 25 or later for development, builds, and runtime.
- Use Maven to manage dependencies, builds, and packaging. Define dependency and plugin versions in `pom.xml`.
- Use picocli for the CLI and its `@Command` annotations for commands and subcommands.
- Use Typesafe Config (`com.typesafe:config`) to parse HOCON configuration. Do not introduce Jackson for configuration mapping.
- Use standard Java console output, optionally with jansi for terminal colors and progress display.

## Java implementation conventions

- Prefer finalized language features available in Java 25. Use `record` for immutable configuration and environment facts where appropriate.
- Use sealed types, pattern matching, and switch expressions where they simplify handling closed sets of states.
- Use text blocks for multiline AI prompt templates.
- Do not enable preview features by default. If needed, document the reason and the build/runtime requirements, including `--enable-preview`.
- Prioritize simplicity and maintainability. Do not introduce unnecessary abstractions merely to use new syntax.

## Concurrency and resource management

- Prefer virtual threads for blocking process waits and log reading. Limit external-process concurrency independently of thread count.
- Use try-with-resources to manage process output streams and other closeable resources.

## Maven build and distribution

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

## Testing

- Do not write unit tests for this repository.
