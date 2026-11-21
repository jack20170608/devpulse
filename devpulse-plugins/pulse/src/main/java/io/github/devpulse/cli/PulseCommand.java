package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.core.bash.BashTaskAdapter;
import io.github.devpulse.core.bash.PlanManifest;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.bash.WorkflowRun;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.PulsePlan;
import io.github.devpulse.execution.TaskFingerprints;
import io.github.devpulse.review.TaskDetails;
import io.github.devpulse.storage.TaskResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
            StopCommand.class,
            RetryCommand.class
        },
        description = "Run a local task or pipeline " + "according to its dependencies.")
public final class PulseCommand implements Callable<Integer> {
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
        var fingerprints = TaskFingerprints.of(plan.tasks(), plan.daemonOptions());
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
                new RunHistory(config.directory())
                        .begin(
                                target,
                                plan.graph(),
                                concurrency,
                                manifest(target, config, plan, concurrency, shell, fingerprints))) {
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
                recordReuse(history, fingerprints);
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

    /**
     * Record the immutable inputs of this run: the selected nodes, their execution fingerprints,
     * and the environment. A later retry compares a fresh configuration against this record, so the
     * nodes that actually ran are the ones recorded, not every task in the file.
     */
    private static PlanManifest manifest(
            String target,
            PulseConfiguration config,
            PulsePlan plan,
            int concurrency,
            BashShell shell,
            Map<String, String> fingerprints) {
        var nodes = new LinkedHashMap<String, PlanManifest.Node>();
        for (var task : plan.tasks()) {
            nodes.put(
                    task.name(),
                    new PlanManifest.Node(
                            task.name(), fingerprints.get(task.name()), task.needs()));
        }
        return new PlanManifest(
                target,
                config.directory().toString(),
                concurrency,
                shell.executable().toString(),
                PulseCommand.class.getPackage().getImplementationVersion(),
                null,
                nodes);
    }

    /**
     * Publish every successful node to the cross-run reuse index.
     *
     * <p>The index is an optimisation: if an entry cannot be written, a later cached run simply
     * reruns that node, so a failure here must not turn a successful run into a failed one.
     */
    static void recordReuse(RunHistory.Writer history, Map<String, String> fingerprints) {
        var snapshot = history.snapshot();
        for (var task : snapshot.tasks()) {
            if (task.state() != RunHistory.State.SUCCESS) {
                continue;
            }
            var fingerprint = fingerprints.get(task.name());
            if (fingerprint == null) {
                continue;
            }
            try {
                history.store()
                        .recordResult(
                                new TaskResult(
                                        task.name(),
                                        fingerprint,
                                        snapshot.id(),
                                        task.finishedAt()));
            } catch (IOException ignored) {
                // Losing an index entry only costs a future rerun.
            }
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
        return CommandOutput.interrupted(json, out, err, "pulse", "execution interrupted.");
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
        return CommandOutput.fail(json, out, err, "pulse", exitCode, code, message, cause);
    }
}
