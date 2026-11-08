package io.github.devpulse.cli;

import io.github.devpulse.core.bash.DaemonManager;
import io.github.devpulse.core.bash.RunHistory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "stop",
        mixinStandardHelpOptions = true,
        versionProvider = PulseCLI.VersionProvider.class,
        description = "Stop recorded local daemon processes."
)
public final class StopCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "TASK",
            description = "Daemon whose recorded instances to stop; defaults to all daemons "
                    + "in the selected run, in reverse dependency order.")
    private String task;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory (default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--run", paramLabel = "ID",
            description = "Select a recorded run instead of the latest.")
    private String run;

    @Option(names = "--instance", paramLabel = "ID",
            description = "Stop one instance of TASK instead of all its instances.")
    private String instance;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            if (instance != null && task == null) {
                throw new IOException("--instance requires a task name.");
            }
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
            var selected = new ArrayList<RunHistory.TaskState>();
            for (var value : snapshot.tasks().reversed()) {
                if ((task == null || task.equals(value.name())) && DaemonManager.recorded(path, value.id())) {
                    selected.add(value);
                }
            }
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
                    if (instance == null) {
                        DaemonManager.stop(path, daemon.id());
                    } else {
                        DaemonManager.stop(path, daemon.id(), instance);
                    }
                    out.println("[" + daemon.name()
                            + "] " + (instance == null ? "All instances" : "Instance " + instance)
                            + " stopped (or already exited).");
                } catch (IOException exception) {
                    failed = true;
                    err.println("[" + daemon.name() + "] "
                            + exception.getMessage());
                    for (var suppressed : exception.getSuppressed()) {
                        err.println("[" + daemon.name() + "] " + suppressed.getMessage());
                    }
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
