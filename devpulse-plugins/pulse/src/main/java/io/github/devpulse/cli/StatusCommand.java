package io.github.devpulse.cli;

import io.github.devpulse.core.environment.EnvironmentGatherer;
import io.github.devpulse.core.execution.RunHistory;
import io.github.devpulse.core.execution.DaemonManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        versionProvider = PulseCLI.VersionProvider.class,
        description = "Show recorded execution status "
                + "or local tool availability."
)
public final class StatusCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory (default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--tools",
            description = "Check Bash and local tool availability.")
    private boolean tools;

    @Option(names = "--bash", paramLabel = "PATH",
            description = "Explicit Bash path for --tools.")
    private Path bash;

    @Option(names = "--run", paramLabel = "ID",
            description = "Show a specific recorded run "
                    + "instead of the latest.")
    private String run;

    @Option(names = "--list",
            description = "List all recorded runs, newest first.")
    private boolean list;

    @Option(names = "--log", paramLabel = "TASK",
            description = "Print a task log from the selected run.")
    private String log;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        if ((tools && (run != null || list || log != null))
                || (list && (run != null || log != null))
                || (!tools && bash != null)) {
            throw new ParameterException(spec.commandLine(),
                    "Use --tools with optional --bash, --list alone, "
                            + "or --run with optional --log.");
        }
        try {
            if (tools) {
                var facts = new EnvironmentGatherer().gather(directory, bash);
                out.println("OS: " + facts.os());
                out.println("Java: " + facts.javaVersion());
                out.println("Bash: " + facts.bash().executable());
                out.println("Availability only; no daemon health checks.");
                for (var tool : facts.tools()) {
                    out.printf("%-12s %-10s %s%n", tool.name(),
                            tool.location().isEmpty()
                                    ? "NOT_FOUND" : "AVAILABLE",
                            tool.location());
                }
                return 0;
            }
            var history = new RunHistory(directory);
            if (run != null) {
                return display(history, history.get(run));
            }
            var records = history.list();
            if (records.isEmpty()) {
                out.println("No pulse executions recorded. Run pulse first.");
                return 0;
            }
            if (list) {
                var now = Instant.now();
                out.printf("%-36s %-12s %-20s %-20s %-20s %s%n",
                        "RUN_ID", "STATE", "TARGET", "STARTED", "FINISHED",
                        "DURATION");
                for (var item : records) {
                    var snapshot = item.snapshot();
                    out.printf("%-36s %-12s %-20s %-20s %-20s %s%n",
                            snapshot.id(),
                            snapshot.state(), snapshot.target(),
                            time(snapshot.startedAt()),
                            time(snapshot.finishedAt()),
                            duration(snapshot.startedAt(),
                                    snapshot.finishedAt(), snapshot.state(),
                                    now));
                    if (item.warning() != null) {
                        err.println(snapshot.id() + ": " + item.warning());
                    }
                }
                if (records.stream().anyMatch(item ->
                        estimated(item.snapshot().state()))) {
                    timingNote();
                }
                return 0;
            }
            return display(history, records.getFirst());
        } catch (IOException exception) {
            err.println("status: " + exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("status: interrupted.");
            return 130;
        }
    }

    private int display(RunHistory history, RunHistory.Observation item)
            throws IOException, InterruptedException {
        var snapshot = item.snapshot();
        var out = spec.commandLine().getOut();
        if (item.warning() != null) {
            spec.commandLine().getErr().println(item.warning());
        }
        if (log != null) {
            var task = snapshot.tasks().stream()
                    .filter(value -> value.name().equals(log))
                    .findFirst().orElseThrow(() -> new IOException(
                            "Task not found in this run: " + log));
            var file = history.logFile(snapshot, task);
            if (!Files.exists(file)) {
                out.println("No log was created for " + log
                        + " (state: " + task.state() + ").");
                return 0;
            }
            try (var reader = Files.newBufferedReader(file,
                    StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.println(line);
                }
            }
            return 0;
        }
        var now = Instant.now();
        out.println("Run ID: " + snapshot.id());
        out.println("Target: " + snapshot.target());
        out.println("State: " + snapshot.state());
        out.println("Started: " + time(snapshot.startedAt()));
        out.println("Finished: " + time(snapshot.finishedAt()));
        out.println("Duration: " + duration(snapshot.startedAt(),
                snapshot.finishedAt(), snapshot.state(), now));
        out.println("Parallelism: " + snapshot.parallelism());
        out.printf("%-24s %-12s %-5s %-20s %-20s %s%n",
                "TASK", "STATE", "EXIT", "STARTED", "FINISHED", "DURATION");
        for (var task : snapshot.tasks()) {
            out.printf("%-24s %-12s %-5s %-20s %-20s %s%n",
                    task.name(), task.state(),
                    task.exitCode() == null ? "-" : task.exitCode(),
                    time(task.startedAt()),
                    time(task.startedAt() == null ? null : task.finishedAt()),
                    duration(task.startedAt(), task.finishedAt(),
                            task.state(), now));
            if (task.error() != null) {
                out.println("  " + task.error());
            }
        }
        var directory = history.runDirectory(snapshot);
        var daemons = snapshot.tasks().stream()
                .filter(task -> DaemonManager.recorded(directory, task.id()))
                .toList();
        if (!daemons.isEmpty()) {
            out.println();
            out.println("Daemon processes (live checks; "
                    + "run state records the pipeline outcome):");
            out.printf("%-24s %-12s %-10s %s%n",
                    "TASK", "STATE", "PID", "SHUTDOWN_HOOK");
            for (var daemon : daemons) {
                var observation = DaemonManager.observe(directory,
                        daemon.id());
                out.printf("%-24s %-12s %-10s %s%n",
                        daemon.name(), observation.state(), observation.pid(),
                        observation.shutdownHook());
                if (observation.detail() != null) {
                    out.println("  " + observation.detail());
                }
            }
        }
        if (estimated(snapshot.state()) || snapshot.tasks().stream()
                .anyMatch(task -> estimated(task.state()))) {
            timingNote();
        }
        return 0;
    }

    private static String time(Instant value) {
        return value == null ? "-"
                : value.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String duration(Instant start, Instant finish,
            RunHistory.State state, Instant now) {
        if (start == null
                || (finish == null && state != RunHistory.State.RUNNING)) {
            return "-";
        }
        var elapsed = Duration.between(start, finish == null ? now : finish);
        if (elapsed.isNegative()) {
            return "-";
        }
        String value;
        if (elapsed.getSeconds() == 0) {
            value = elapsed.toMillis() + "ms";
        } else {
            var amount = elapsed.getSeconds() + elapsed.getNano() / 1e9;
            var unit = "s";
            if (elapsed.getSeconds() >= 3600) {
                amount /= 3600;
                unit = "h";
            } else if (elapsed.getSeconds() >= 60) {
                amount /= 60;
                unit = "m";
            }
            value = String.format(Locale.ROOT, "%.2f", amount)
                    .replaceAll("\\.?0+$", "") + unit;
        }
        return (estimated(state) ? "~" : "") + value
                + (finish == null ? " (running)" : "");
    }

    private static boolean estimated(RunHistory.State state) {
        return state == RunHistory.State.INTERRUPTED
                || state == RunHistory.State.CANCELLED;
    }

    private void timingNote() {
        spec.commandLine().getOut().println(
                "~ marks estimated timing; finish may be cancellation "
                        + "or recovery time, not the actual process exit.");
    }
}
