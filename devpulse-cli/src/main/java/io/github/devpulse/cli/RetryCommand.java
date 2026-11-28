package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.RetryPlan;
import io.github.devpulse.execution.RunHistory;
import io.github.devpulse.execution.TaskFingerprints;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "retry",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description =
                "Show what a retry of a recorded run would rerun and reuse. "
                        + "Execution arrives in a later stage; this stage reports only.")
public final class RetryCommand implements Callable<Integer> {
    @Spec private CommandSpec spec;

    @Parameters(index = "0", arity = "1", paramLabel = "RUN_ID", description = "Run to retry.")
    private String runId;

    @Option(
            names = "--directory",
            defaultValue = ".",
            description =
                    "Project directory containing .devpulse.yaml " + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--jobs", paramLabel = "N", description = "Concurrency limit for the retry.")
    private Integer jobs;

    @Option(
            names = "--dry-run",
            description = "Report the rerun and reuse sets without executing anything.")
    private boolean dryRun;

    @Option(names = "--force", description = "Allow retrying a run that is still executing.")
    private boolean force;

    @Option(names = "--from-scratch", description = "Ignore every reuse and rerun all nodes.")
    private boolean fromScratch;

    @Option(
            names = "--format",
            paramLabel = "FORMAT",
            defaultValue = "text",
            description = "Result format: text (default) or json.")
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
                    "retry",
                    ExitCodes.USAGE,
                    "USAGE",
                    "--jobs must be a positive integer.",
                    null);
        }
        if (!dryRun) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "retry",
                    ExitCodes.RETRY_REFUSED,
                    "RETRY_UNAVAILABLE",
                    "Retry execution is not implemented yet; "
                            + "rerun with --dry-run to inspect the retry plan.",
                    null);
        }
        try {
            var history = new RunHistory(directory.toAbsolutePath().normalize());
            var parent = history.get(runId);
            if (!force
                    && (parent.snapshot().state() == RunHistory.State.RUNNING
                            || parent.snapshot().state() == RunHistory.State.PENDING)) {
                return CommandOutput.fail(
                        json,
                        out,
                        err,
                        "retry",
                        ExitCodes.RETRY_REFUSED,
                        "RETRY_REFUSED",
                        "Run "
                                + runId
                                + " is still "
                                + parent.snapshot().state()
                                + "; wait for it or pass --force.",
                        null);
            }
            var manifest = history.plan(runId);
            var config =
                    PulseConfiguration.load(
                            directory,
                            WorkflowFiles.resolve(
                                    directory, Path.of(".workflow"), manifest.target()));
            var plan = config.plan();
            var fingerprints = TaskFingerprints.of(plan.tasks(), plan.daemonOptions());
            var needs = new LinkedHashMap<String, List<String>>();
            var daemons = new LinkedHashSet<String>();
            for (var task : plan.tasks()) {
                needs.put(task.name(), task.needs());
                if (task.daemon() != null) {
                    daemons.add(task.name());
                }
            }
            var states = new LinkedHashMap<String, RunHistory.State>();
            for (var task : parent.snapshot().tasks()) {
                states.put(task.name(), task.state());
            }
            var retry =
                    RetryPlan.compute(manifest, states, fingerprints, needs, daemons, fromScratch);
            if (json) {
                out.println(
                        PulseJson.retry(
                                runId, true, retry.rerun(), retry.reuse(), retry.reasons()));
                out.flush();
                return ExitCodes.OK;
            }
            out.println(
                    "Retry plan for "
                            + runId
                            + " (target "
                            + manifest.target()
                            + ", "
                            + retry.rerun().size()
                            + " to rerun, "
                            + retry.reuse().size()
                            + " to reuse)");
            if (!retry.rerun().isEmpty()) {
                out.println("Rerun:");
                for (var name : retry.rerun()) {
                    out.println("  [" + name + "] " + retry.reasons().get(name));
                }
            }
            if (!retry.reuse().isEmpty()) {
                out.println("Reuse:");
                retry.reuse().forEach(name -> out.println("  [" + name + "]"));
            }
            if (!retry.reuse().isEmpty()) {
                err.println(
                        "retry: reused nodes keep their parent results; changes to working-tree "
                                + "files are not detected. Use --from-scratch to rerun everything.");
            }
            out.flush();
            return ExitCodes.OK;
        } catch (ConfigException.BadValue exception) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "retry",
                    ExitCodes.CONFIG_GRAPH,
                    "CONFIG_GRAPH",
                    exception.getMessage(),
                    exception);
        } catch (IOException exception) {
            var message = exception.getMessage() == null ? "" : exception.getMessage();
            var targetProblem =
                    message.startsWith("Run not found: ")
                            || message.startsWith("Invalid run ID: ")
                            || message.startsWith("Unknown task or pipeline: ");
            var manifestProblem = message.contains("plan manifest");
            int code;
            String name;
            if (manifestProblem) {
                code = ExitCodes.RETRY_REFUSED;
                name = "RETRY_REFUSED";
            } else if (targetProblem) {
                code = ExitCodes.TARGET_INVALID;
                name = "TARGET_INVALID";
            } else {
                code = ExitCodes.INFRASTRUCTURE;
                name = "INFRASTRUCTURE";
            }
            return CommandOutput.fail(json, out, err, "retry", code, name, message, exception);
        }
    }
}
