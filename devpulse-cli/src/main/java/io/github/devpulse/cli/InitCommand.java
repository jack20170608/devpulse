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
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Write a workflow template under .workflow/.")
public final class InitCommand implements Callable<Integer> {
    private static final String TEMPLATE =
            """
            # 一个文件定义一个 workflow；文件名即 workflow id。
            # One file defines one workflow; the file name is its id.
            name: %s

            tasks:
              - id: check
                kind: bash
                spec:
                  run: echo 'devpulse: replace with a project check'

              - id: build
                kind: bash
                needs: [check]
                spec:
                  run: echo 'devpulse: replace with a project build'
            """;

    @Spec private CommandSpec spec;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "WORKFLOW",
            description = "Workflow id to create (default: workflow).")
    private String workflow;

    @Option(
            names = "--directory",
            defaultValue = ".",
            description = "Project directory to initialize " + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--force", description = "Overwrite an existing workflow file.")
    private boolean force;

    @Override
    public Integer call() {
        var root = directory.toAbsolutePath().normalize();
        var id = workflow == null ? "workflow" : workflow;
        var target = root.resolve(".workflow").resolve(id + ".yaml");
        var err = spec.commandLine().getErr();
        if (!Files.isDirectory(root)) {
            err.println("init: project directory does not exist: " + root);
            return 1;
        }
        try {
            Files.createDirectories(target.getParent());
            if (force
                    && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                err.println("init: workflow is not a regular file: " + target);
                return 1;
            }
            Files.writeString(
                    target,
                    TEMPLATE.formatted(id),
                    StandardCharsets.UTF_8,
                    force ? StandardOpenOption.CREATE : StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            spec.commandLine()
                    .getOut()
                    .println(
                            "Initialized "
                                    + target
                                    + ". Edit the example tasks, then run: devpulse run "
                                    + id);
            return 0;
        } catch (FileAlreadyExistsException exception) {
            err.println("init: workflow already exists: " + target + ". Use --force to overwrite.");
            return 1;
        } catch (IOException exception) {
            err.println("init: could not write " + target + ": " + exception.getMessage());
            return 1;
        }
    }
}
