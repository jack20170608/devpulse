package io.github.devpulse.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        versionProvider = DevPulseCLI.VersionProvider.class,
        description = "Initialize a local .devpulse.conf HOCON configuration."
)
public final class InitCommand implements Callable<Integer> {
    private static final String DEFAULT_CONFIGURATION = """
            # 示例任务仅打印消息，请替换为实际项目命令。
            # Example tasks only print messages; replace with project commands.
            version = 1
            parallelism = 4

            tools = [
              {
                name = check
                cmd = "echo 'devpulse: replace with a project check'"
              },
              {
                name = build
                cmd = "echo 'devpulse: replace with a project build'"
                needs = [check]
              }
            ]

            pipelines {
              dev {
                description = "示例检查与构建 / Example check and build"
                tasks = [check, build]
              }
            }
            """;

    @Spec
    private CommandSpec spec;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory to initialize "
                    + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--force",
            description = "Overwrite an existing .devpulse.conf file.")
    private boolean force;

    @Override
    public Integer call() {
        var root = directory.toAbsolutePath().normalize();
        var target = root.resolve(".devpulse.conf");
        var err = spec.commandLine().getErr();
        if (!Files.isDirectory(root)) {
            err.println("init: project directory does not exist: " + root);
            return 1;
        }
        try {
            if (force && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target,
                    LinkOption.NOFOLLOW_LINKS)) {
                err.println("init: configuration is not a regular file: "
                        + target);
                return 1;
            }
            Files.writeString(target, DEFAULT_CONFIGURATION,
                    StandardCharsets.UTF_8,
                    force ? StandardOpenOption.CREATE
                            : StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            spec.commandLine().getOut().println("Initialized " + target
                    + ". Review the example commands before running tasks.");
            return 0;
        } catch (FileAlreadyExistsException exception) {
            err.println("init: configuration already exists: " + target
                    + ". Use --force to overwrite.");
            return 1;
        } catch (IOException exception) {
            err.println("init: could not write " + target + ": "
                    + exception.getMessage());
            return 1;
        }
    }
}
