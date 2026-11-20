package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.core.bash.BashTaskAdapter;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.bash.WorkflowRun;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.PulsePlan;
import io.github.devpulse.review.TaskDetails;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "pulse",
        mixinStandardHelpOptions = true,
        versionProvider = PulseCLI.VersionProvider.class,
        subcommands = {
            InitCommand.class,
            ReviewCommand.class,
            StatusCommand.class,
            StopCommand.class
        },
        description = "Run a local task or pipeline " + "according to its dependencies.")
public final class PulseCommand implements Callable<Integer> {
    /** Output format for the command's own result. */
    public enum Format {
        text,
        json
    }

    @Spec private CommandSpec spec;

    @Parameters(
            index = "0",
            arity = "0..1",
            defaultValue = "dev",
            paramLabel = "TARGET",
            description = "Task or pipeline to run " + "(default: ${DEFAULT-VALUE}).")
    private String target;

    @Option(
            names = "--directory",
            defaultValue = ".",
            description =
                    "Project directory containing .devpulse.yaml " + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(
            names = "--bash",
            paramLabel = "PATH",
            description = "Explicit path to Bash (Git Bash on Windows).")
    private Path bash;

    @Option(
            names = "--jobs",
            paramLabel = "N",
            description = "Maximum concurrent tasks; overrides parallelism.")
    private Integer jobs;

    @Option(names = "--dry-run", description = "Validate and print the plan without running tasks.")
    private boolean dryRun;

    @Option(
            names = "--format",
            paramLabel = "FORMAT",
            defaultValue = "text",
            description =
                    "Result format: text (default) or json. "
                            + "JSON prints exactly one document on stdout; "
                            + "task logs then go only to their log files.")
    private Format format;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var json = format == Format.json;

        if (jobs != null && jobs <= 0) {
            return fail(
                    json,
                    out,
                    err,
                    ExitCodes.USAGE,
                    "USAGE",
                    "--jobs must be a positive integer.",
                    null);
        }

        PulseConfiguration config;
        try {
            config = PulseConfiguration.load(directory);
        } catch (ConfigException | IOException exception) {
            return fail(
                    json,
                    out,
                    err,
                    ExitCodes.CONFIG_INVALID,
                    "CONFIG_INVALID",
                    exception.getMessage(),
                    exception);
        }

        PulsePlan plan;
        try {
            plan = config.plan(target);
        } catch (ConfigException.BadValue exception) {
            return fail(
                    json,
                    out,
                    err,
                    ExitCodes.CONFIG_GRAPH,
                    "CONFIG_GRAPH",
                    exception.getMessage(),
                    exception);
        } catch (IOException exception) {
            return fail(
                    json,
                    out,
                    err,
                    ExitCodes.TARGET_INVALID,
                    "TARGET_INVALID",
                    exception.getMessage(),
                    exception);
        }

        var concurrency = jobs == null ? config.parallelism() : jobs;
        if (dryRun) {
            if (json) {
                out.println(PulseJson.plan(target, config.directory(), concurrency, plan));
            } else {
                out.println(
                        "Plan: "
                                + target
                                + " ("
                                + plan.tasks().size()
                                + " tasks, parallelism "
                                + concurrency
                                + ")");
                for (var task : plan.tasks()) {
                    out.println("[" + task.name() + "] needs: " + task.needs());
                    out.println(task.command());
                    TaskDetails.daemonDetails(task, plan.daemonOptions().get(task.name()))
                            .forEach(out::println);
                }
            }
            out.flush();
            return ExitCodes.OK;
        }

        BashShell shell;
        try {
            shell = BashShell.discover(bash);
        } catch (IOException exception) {
            return fail(
                    json,
                    out,
                    err,
                    ExitCodes.ENVIRONMENT,
                    "ENVIRONMENT",
                    exception.getMessage(),
                    exception);
        } catch (InterruptedException exception) {
            return interrupted(json, out, err);
        }

        try (var history =
                new RunHistory(config.directory()).begin(target, plan.graph(), concurrency)) {
            if (!json) {
                out.println("Run ID: " + history.id());
                out.flush();
            }
            PrintWriter console = json ? new PrintWriter(Writer.nullWriter()) : out;
            var adapter = new BashTaskAdapter(shell, config.directory(), console, history);
            var run = new WorkflowRun(plan.workflow(adapter), history, concurrency);
            var hook = Thread.ofPlatform().unstarted(run::cancel);
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                var result = run.execute();
                var ok = result.state() == RunHistory.State.SUCCESS;
                if (json) {
                    out.println(
                            PulseJson.run(
                                    target,
                                    config.directory(),
                                    history.id(),
                                    result.state().name(),
                                    result.succeeded(),
                                    result.failed(),
                                    result.skipped(),
                                    ok));
                } else {
                    out.println(
                            "[pulse] Finished: "
                                    + result.succeeded()
                                    + " succeeded, "
                                    + result.failed()
                                    + " failed, "
                                    + result.skipped()
                                    + " skipped.");
                }
                out.flush();
                return ok ? ExitCodes.OK : ExitCodes.TASK_FAILED;
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException exception) {
                    // Shutdown is already in progress; the hook owns cancellation.
                }
            }
        } catch (InterruptedException exception) {
            return interrupted(json, out, err);
        } catch (IOException exception) {
            return fail(
                    json,
                    out,
                    err,
                    ExitCodes.INFRASTRUCTURE,
                    "INFRASTRUCTURE",
                    exception.getMessage(),
                    exception);
        }
    }

    private static Integer interrupted(boolean json, PrintWriter out, PrintWriter err) {
        Thread.currentThread().interrupt();
        return fail(
                json,
                out,
                err,
                ExitCodes.INTERRUPTED,
                "INTERRUPTED",
                "execution interrupted.",
                null);
    }

    /**
     * Report a classified failure. JSON mode writes one error document to stdout; text mode keeps
     * the previous single-line message on stderr. The returned code is the process exit code.
     */
    private static Integer fail(
            boolean json,
            PrintWriter out,
            PrintWriter err,
            int exitCode,
            String code,
            String message,
            Exception cause) {
        if (json) {
            out.println(
                    PulseJson.error(
                            exitCode, code, message, configFilename(cause), configLine(cause)));
            out.flush();
        } else {
            err.println("pulse: " + message);
        }
        return exitCode;
    }

    private static String configFilename(Exception cause) {
        return cause instanceof ConfigException bad ? bad.origin().filename() : null;
    }

    private static Integer configLine(Exception cause) {
        return cause instanceof ConfigException bad ? bad.origin().lineNumber() : null;
    }
}
