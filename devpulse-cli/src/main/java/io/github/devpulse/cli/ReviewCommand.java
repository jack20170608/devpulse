package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.PulsePlan;
import io.github.devpulse.review.ReviewTui;
import io.github.devpulse.review.TaskDetails;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description =
                "Validate and review a task or pipeline plan " + "without executing commands.")
public final class ReviewCommand implements Callable<Integer> {
    /** Default concurrency until the application configuration exposes it. */
    private static final int DEFAULT_PARALLELISM = 4;

    @Spec private CommandSpec spec;

    @Parameters(
            index = "0",
            arity = "0..1",
            defaultValue = "dev",
            paramLabel = "WORKFLOW",
            description = "Task or pipeline to review " + "(default: ${DEFAULT-VALUE}).")
    private String workflow;

    @Option(
            names = "--directory",
            defaultValue = ".",
            description =
                    "Project directory containing .devpulse.yaml " + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(
            names = "--jobs",
            paramLabel = "N",
            description = "Review with this concurrency limit; " + "overrides parallelism.")
    private Integer jobs;

    @Option(names = "--plain", description = "Print a plain-text plan instead of opening the TUI.")
    private boolean plain;

    @Option(
            names = "--format",
            paramLabel = "FORMAT",
            defaultValue = "text",
            description =
                    "Result format: text (default) or json. "
                            + "JSON emits the same plan document as "
                            + "pulse --dry-run --format json and never opens the TUI.")
    private OutputFormat format;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var json = format == OutputFormat.json;
        if (jobs != null && jobs <= 0) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "review",
                    ExitCodes.USAGE,
                    "USAGE",
                    "--jobs must be a positive integer.",
                    null);
        }
        PulseConfiguration config;
        try {
            var workflowFile =
                    WorkflowFiles.resolve(
                            directory, Path.of(".workflow"), workflow);
            config = PulseConfiguration.load(directory, workflowFile);
        } catch (ConfigException | IOException exception) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "review",
                    ExitCodes.CONFIG_INVALID,
                    "CONFIG_INVALID",
                    exception.getMessage(),
                    exception);
        }
        PulsePlan plan;
        try {
            plan = config.plan();
        } catch (ConfigException.BadValue exception) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "review",
                    ExitCodes.CONFIG_GRAPH,
                    "CONFIG_GRAPH",
                    exception.getMessage(),
                    exception);
        } catch (IOException exception) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "review",
                    ExitCodes.TARGET_INVALID,
                    "TARGET_INVALID",
                    exception.getMessage(),
                    exception);
        }
        var concurrency = jobs == null ? DEFAULT_PARALLELISM : jobs;
        if (json) {
            out.println(PulseJson.plan(config.workflowId(), config.directory(), concurrency, plan));
            out.flush();
            return ExitCodes.OK;
        }
        try {
            if (!plain && ReviewTui.show(config.workflowId(), config, plan, concurrency)) {
                return ExitCodes.OK;
            }
            out.println(
                    "Plan: "
                            + config.workflowId()
                            + " ("
                            + plan.tasks().size()
                            + " tasks, parallelism "
                            + concurrency
                            + ")");
            out.println("Project directory: " + config.directory());
            out.println();
            out.println("Dependencies (task <- prerequisites):");
            for (var task : plan.tasks()) {
                var dependencies =
                        task.needs().isEmpty()
                                ? "(none)"
                                : task.needs().stream()
                                        .map(name -> "[" + name + "]")
                                        .collect(Collectors.joining(", "));
                out.println("  [" + task.name() + "] <- " + dependencies);
            }
            out.println();
            out.println("Topological order and commands " + "(not a fixed execution schedule):");
            var index = 1;
            for (var task : plan.tasks()) {
                out.println(index++ + ". [" + task.name() + "]");
                out.println(task.command());
                TaskDetails.daemonDetails(task, plan.daemonOptions().get(task.name()))
                        .forEach(out::println);
                out.println();
            }
            out.println(
                    "Static review only: no commands executed. "
                            + "Configuration validity does not guarantee "
                            + "command safety or tool availability.");
            out.flush();
            return ExitCodes.OK;
        } catch (IOException exception) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "review",
                    ExitCodes.INFRASTRUCTURE,
                    "INFRASTRUCTURE",
                    exception.getMessage(),
                    exception);
        } catch (InterruptedException exception) {
            return CommandOutput.interrupted(json, out, err, "review", "interrupted.");
        }
    }
}
