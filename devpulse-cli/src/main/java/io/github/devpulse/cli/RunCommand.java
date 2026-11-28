package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.bash.BashShell;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.PulsePlan;
import io.github.devpulse.execution.RunHistory;
import io.github.devpulse.execution.RunService;
import io.github.devpulse.execution.WorkflowRun;
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
        name = "run",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            DoctorCommand.class,
            InitCommand.class,
            ReviewCommand.class,
            StatusCommand.class,
            StopCommand.class,
            RetryCommand.class
        },
        description = "Run a local task or pipeline " + "according to its dependencies.")
public final class RunCommand implements Callable<Integer> {
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
    private OutputFormat format;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var json = format == OutputFormat.json;

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

        try {
            var outcome =
                    RunService.execute(
                            shell,
                            config.directory(),
                            target,
                            plan,
                            concurrency,
                            json ? new PrintWriter(Writer.nullWriter()) : out,
                            id -> {
                                if (!json) {
                                    out.println("Run ID: " + id);
                                    out.flush();
                                }
                            });
            var result = outcome.result();
            var ok = result.state() == RunHistory.State.SUCCESS;
            if (json) {
                out.println(
                        PulseJson.run(
                                target,
                                config.directory(),
                                outcome.runId(),
                                result.state().name(),
                                result.succeeded(),
                                result.failed(),
                                result.skipped(),
                                result.daemons().name(),
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
            return exitCodeFor(result);
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

    /**
     * Map a run to the exit code that names what to fix. A daemon problem outranks an ordinary task
     * failure, because the daemon is the precondition for everything that depends on it.
     */
    static int exitCodeFor(WorkflowRun.Result result) {
        if (result.state() == RunHistory.State.SUCCESS) {
            return ExitCodes.OK;
        }
        return switch (result.daemons()) {
            case NEVER_READY -> ExitCodes.DAEMON_NOT_READY;
            case LOST -> ExitCodes.DAEMON_LOST;
            case NONE -> ExitCodes.TASK_FAILED;
        };
    }

    private static Integer interrupted(boolean json, PrintWriter out, PrintWriter err) {
        return CommandOutput.interrupted(json, out, err, "devpulse", "execution interrupted.");
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
        return CommandOutput.fail(json, out, err, "devpulse", exitCode, code, message, cause);
    }
}
