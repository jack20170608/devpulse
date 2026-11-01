package io.github.devpulse.cli;

import io.github.devpulse.execution.DaemonManager;
import io.github.devpulse.execution.RunHistory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "stop",
        mixinStandardHelpOptions = true,
        versionProvider = DevPulseCLI.VersionProvider.class,
        description = "Stop recorded local daemon processes."
)
public final class StopCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "TASK",
            description = "Daemon to stop; defaults to all daemons "
                    + "in the selected run, in reverse dependency order.")
    private String task;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory (default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--run", paramLabel = "ID",
            description = "Select a recorded run instead of the latest.")
    private String run;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            var history = new RunHistory(directory);
            RunHistory.Snapshot snapshot;
            if (run == null) {
                var records = history.list();
                if (records.isEmpty()) {
                    out.println("No pulse executions recorded.");
                    return 0;
                }
                snapshot = records.getFirst().snapshot();
            } else {
                snapshot = history.get(run).snapshot();
            }
            var path = history.runDirectory(snapshot);
            var selected = snapshot.tasks().reversed().stream()
                    .filter(value -> task == null
                            || task.equals(value.name()))
                    .filter(value -> DaemonManager.recorded(path, value.id()))
                    .toList();
            if (selected.isEmpty()) {
                if (task != null) {
                    throw new IOException(
                            "No daemon process recorded for task: " + task);
                }
                out.println("No daemon processes recorded in this run.");
                return 0;
            }
            var failed = false;
            for (var daemon : selected) {
                try {
                    DaemonManager.stop(path, daemon.id());
                    out.println("[" + daemon.name()
                            + "] Stopped (or already exited).");
                } catch (IOException exception) {
                    failed = true;
                    err.println("[" + daemon.name() + "] "
                            + exception.getMessage());
                }
            }
            return failed ? 1 : 0;
        } catch (IOException exception) {
            err.println("stop: " + exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("stop: interrupted.");
            return 130;
        }
    }
}
