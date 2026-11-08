package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.bash.BashTaskAdapter;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.bash.WorkflowRun;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.review.TaskDetails;
import java.io.IOException;
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
        description = "Run a local task or pipeline "
                + "according to its dependencies."
)
public final class PulseCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", defaultValue = "dev",
            paramLabel = "TARGET",
            description = "Task or pipeline to run "
                    + "(default: ${DEFAULT-VALUE}).")
    private String target;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory containing .devpulse.yaml "
                    + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--bash", paramLabel = "PATH",
            description = "Explicit path to Bash (Git Bash on Windows).")
    private Path bash;

    @Option(names = "--jobs", paramLabel = "N",
            description = "Maximum concurrent tasks; overrides parallelism.")
    private Integer jobs;

    @Option(names = "--dry-run",
            description = "Validate and print the plan without running tasks.")
    private boolean dryRun;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            if (jobs != null && jobs <= 0) {
                err.println("pulse: --jobs must be a positive integer.");
                return 1;
            }
            var config = PulseConfiguration.load(directory);
            var plan = config.plan(target);
            var concurrency = jobs == null ? config.parallelism() : jobs;
            out.println("Plan: " + target + " (" + plan.tasks().size()
                    + " tasks, parallelism " + concurrency + ")");
            if (dryRun) {
                for (var task : plan.tasks()) {
                    out.println("[" + task.name() + "] needs: "
                            + task.needs());
                    out.println(task.command());
                    TaskDetails.daemonDetails(task, plan.daemonOptions().get(task.name())).forEach(out::println);
                }
                out.flush();
                return 0;
            }
            var shell = BashShell.discover(bash);
            try (var history = new RunHistory(config.directory())
                    .begin(target, plan.graph(), concurrency)) {
                out.println("Run ID: " + history.id());
                out.flush();
                var adapter = new BashTaskAdapter(shell, config.directory(), out, history);
                var run = new WorkflowRun(plan.workflow(adapter), history, concurrency);
                var hook = Thread.ofPlatform().unstarted(run::cancel);
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    var result = run.execute();
                    out.println("[pulse] Finished: " + result.succeeded() + " succeeded, "
                            + result.failed() + " failed, " + result.skipped() + " skipped.");
                    out.flush();
                    return result.state() == RunHistory.State.SUCCESS ? 0 : 1;
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException exception) {
                        // Shutdown is already in progress; the hook owns cancellation.
                    }
                }
            }
        } catch (ConfigException | IOException exception) {
            err.println("pulse: " + exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("pulse: execution interrupted.");
            return 130;
        }
    }
}
