package io.github.devpulse.execution;

import com.typesafe.config.ConfigException;
import io.github.devpulse.environment.BashShell;
import io.github.devpulse.execution.PulseConfiguration.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class DaemonManager {
    private enum Identity { MATCH, GONE, UNKNOWN }

    public record Observation(String state, long pid, boolean shutdownHook,
            String detail) {}

    private record Owner(long pid, Instant startedAt) {
        Identity identity() {
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) {
                return Identity.GONE;
            }
            var current = process.get().info().startInstant();
            if (current.isEmpty()) {
                return Identity.UNKNOWN;
            }
            return current.get().equals(startedAt)
                    ? Identity.MATCH : Identity.GONE;
        }
    }

    private record Record(Path directory, String runId, String taskId,
            String name, Path project, Path bash, Owner owner,
            PulseConfiguration.Daemon configuration,
            Instant readyAt, Instant stoppedAt) {
        Record ready() {
            return new Record(directory, runId, taskId, name, project,
                    bash, owner,
                    configuration, Instant.now(), stoppedAt);
        }

        Record stopped() {
            return new Record(directory, runId, taskId, name, project,
                    bash, owner,
                    configuration, readyAt, Instant.now());
        }
    }

    private static final class Managed {
        private final Process process;
        private volatile Record record;

        private Managed(Process process, Record record) {
            this.process = process;
            this.record = record;
        }
    }

    private final RunHistory.Writer history;
    private final BashShell bash;
    private final Path project;
    private final Map<String, Managed> managed = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private volatile boolean monitoring = true;

    public DaemonManager(RunHistory.Writer history, BashShell bash,
            Path project) {
        this.history = history;
        this.bash = bash;
        this.project = project;
    }

    public void start(Task task) throws IOException {
        var id = history.taskId(task.name());
        var directory = history.directory().resolve("daemons");
        Files.createDirectories(directory);
        var log = history.directory().resolve("logs").resolve(id + ".log");
        Files.createFile(log);
        var start = directory.resolve(id + "-start.sh");
        script(start, task.command());
        script(directory.resolve(id + "-ready.sh"),
                task.daemon().ready().command());
        if (task.daemon().stopCommand() != null) {
            script(directory.resolve(id + "-stop.sh"),
                    task.daemon().stopCommand());
        }
        var process = bash.script(project).redirectInput(start.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        try {
            process.getOutputStream().close();
            var startedAt = process.info().startInstant().orElseThrow(
                    () -> new IOException(
                            "Cannot record daemon process identity."));
            var record = new Record(history.directory(), history.id(), id,
                    task.name(), project, bash.executable(),
                    new Owner(process.pid(), startedAt),
                    task.daemon(), null, null);
            save(history.directory(), record);
            managed.put(task.name(), new Managed(process, record));
        } catch (IOException | RuntimeException exception) {
            terminate(process.toHandle());
            throw exception;
        }
    }

    public void awaitReady(String name, ExecutorService pool)
            throws IOException, InterruptedException {
        var daemon = managed.get(name);
        var ready = daemon.record.configuration().ready();
        var deadline = System.nanoTime() + ready.timeout().toNanos();
        while (true) {
            if (!daemon.process.isAlive()) {
                throw new IOException("Daemon exited before readiness with "
                        + "code " + daemon.process.exitValue() + ".");
            }
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("Daemon readiness timed out after "
                        + ready.timeout() + ".");
            }
            var code = check(daemon.record,
                    Duration.ofNanos(Math.min(remaining,
                            ready.checkTimeout().toNanos())), null);
            if (code == 0 && daemon.process.isAlive()) {
                daemon.record = daemon.record.ready();
                save(history.directory(), daemon.record);
                pool.submit(() -> monitor(name, daemon));
                return;
            }
            remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,
                        ready.interval().toNanos()));
            }
        }
    }

    private void monitor(String name, Managed daemon) {
        try {
            while (monitoring) {
                Thread.sleep(daemon.record.configuration().ready().interval());
                if (!monitoring) {
                    return;
                }
                var failure = failure(daemon);
                if (failure != null && monitoring) {
                    failures.putIfAbsent(name, failure);
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            if (monitoring) {
                failures.putIfAbsent(name,
                        "Daemon health check failed: "
                                + exception.getMessage());
            }
        }
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    public void finishMonitoring() throws InterruptedException {
        monitoring = false;
        for (var entry : managed.entrySet()) {
            if (entry.getValue().record.readyAt() == null) {
                continue;
            }
            try {
                var failure = failure(entry.getValue());
                if (failure != null) {
                    failures.putIfAbsent(entry.getKey(), failure);
                }
            } catch (IOException exception) {
                failures.putIfAbsent(entry.getKey(),
                        "Daemon health check failed: "
                                + exception.getMessage());
            }
        }
    }

    public void stopHooks(TaskPlan plan)
            throws IOException, InterruptedException {
        monitoring = false;
        IOException failure = null;
        for (var task : plan.tasks().reversed()) {
            var daemon = managed.get(task.name());
            if (daemon == null
                    || !daemon.record.configuration().shutdownHook()) {
                continue;
            }
            try {
                stop(history.directory(), daemon.record.taskId());
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static boolean recorded(Path directory, String taskId) {
        return Files.isRegularFile(file(directory, taskId));
    }

    public static Observation observe(Path directory, String taskId)
            throws IOException, InterruptedException {
        var record = read(directory, taskId);
        return switch (record.owner().identity()) {
            case UNKNOWN -> new Observation("UNKNOWN", record.owner().pid(),
                    record.configuration().shutdownHook(),
                    "Process identity cannot be confirmed.");
            case GONE -> new Observation(record.stoppedAt() == null
                    ? "EXITED" : "STOPPED", record.owner().pid(),
                    record.configuration().shutdownHook(), null);
            case MATCH -> {
                var ready = record.configuration().ready();
                try {
                    var code = check(record,
                            ready.checkTimeout(), null);
                    var identity = record.owner().identity();
                    if (identity == Identity.UNKNOWN) {
                        yield new Observation("UNKNOWN", record.owner().pid(),
                                record.configuration().shutdownHook(),
                                "Process identity cannot be confirmed.");
                    }
                    if (identity == Identity.GONE) {
                        yield new Observation("EXITED", record.owner().pid(),
                                record.configuration().shutdownHook(),
                                "Process exited during the readiness check.");
                    }
                    yield new Observation(code == 0 ? "READY"
                            : record.readyAt() == null
                                    ? "STARTING" : "NOT_READY",
                            record.owner().pid(),
                            record.configuration().shutdownHook(),
                            code == 0 ? null
                                    : "Readiness check failed or timed out.");
                } catch (IOException exception) {
                    yield new Observation("UNKNOWN", record.owner().pid(),
                            record.configuration().shutdownHook(),
                            "Cannot check readiness: "
                                    + exception.getMessage());
                }
            }
        };
    }

    public static void stop(Path directory, String taskId)
            throws IOException, InterruptedException {
        var path = file(directory, taskId);
        if (!Files.isRegularFile(path)) {
            throw new IOException("No daemon process was recorded.");
        }
        try (var channel = FileChannel.open(path.resolveSibling(
                taskId + ".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Daemon cleanup is already in progress.");
            }
            var record = read(directory, taskId);
            var identity = record.owner().identity();
            if (identity == Identity.UNKNOWN) {
                throw new IOException(
                        "Refusing to stop an unconfirmed process identity.");
            }
            if (identity == Identity.GONE) {
                save(directory, record.stopped());
                return;
            }
            var owners = new ArrayList<Owner>();
            var handle = ProcessHandle.of(record.owner().pid()).orElse(null);
            if (handle == null) {
                save(directory, record.stopped());
                return;
            }
            handle.descendants().toList().reversed().forEach(child ->
                    child.info().startInstant().ifPresent(time ->
                            owners.add(new Owner(child.pid(), time))));
            owners.add(record.owner());
            identity = record.owner().identity();
            if (identity == Identity.GONE) {
                save(directory, record.stopped());
                return;
            }
            if (identity == Identity.UNKNOWN) {
                throw new IOException(
                        "Refusing to stop an unconfirmed process identity.");
            }
            var deadline = System.nanoTime()
                    + record.configuration().stopTimeout().toNanos();
            IOException stopFailure = null;
            var command = record.configuration().stopCommand();
            if (command != null) {
                try {
                    var code = check(record,
                            record.configuration().stopTimeout(),
                            directory.resolve("logs")
                                    .resolve(taskId + ".log"));
                    if (code != 0) {
                        stopFailure = new IOException(code == -1
                                ? "Daemon stop command timed out."
                                : "Daemon stop command exited with code "
                                        + code + ".");
                    }
                } catch (IOException exception) {
                    stopFailure = exception;
                }
            } else {
                signal(owners, false);
            }
            awaitExit(owners, deadline);
            signal(owners, true);
            awaitExit(owners,
                    System.nanoTime() + Duration.ofSeconds(2).toNanos());
            if (owners.stream().anyMatch(owner ->
                    owner.identity() != Identity.GONE)) {
                throw new IOException(
                        "Daemon process cleanup could not be confirmed.");
            }
            save(directory, record.stopped());
            if (stopFailure != null) {
                throw stopFailure;
            }
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Daemon cleanup is already in progress.",
                    exception);
        }
    }

    private static void awaitExit(List<Owner> owners, long deadline)
            throws InterruptedException {
        while (owners.stream().anyMatch(owner ->
                owner.identity() != Identity.GONE)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void signal(List<Owner> owners, boolean force) {
        for (var owner : owners) {
            if (owner.identity() == Identity.MATCH) {
                ProcessHandle.of(owner.pid()).ifPresent(process -> {
                    if (force) {
                        process.destroyForcibly();
                    } else {
                        process.destroy();
                    }
                });
            }
        }
    }

    private static String failure(Managed daemon)
            throws IOException, InterruptedException {
        if (!daemon.process.isAlive()) {
            return "Daemon exited unexpectedly with code "
                    + daemon.process.exitValue() + ".";
        }
        var ready = daemon.record.configuration().ready();
        var code = check(daemon.record, ready.checkTimeout(), null);
        if (!daemon.process.isAlive()) {
            return "Daemon exited unexpectedly with code "
                    + daemon.process.exitValue() + ".";
        }
        return code == 0 ? null : "Daemon lost readiness.";
    }

    private static int check(Record record, Duration timeout, Path log)
            throws IOException, InterruptedException {
        var script = record.directory().resolve("daemons").resolve(
                record.taskId() + (log == null ? "-ready.sh" : "-stop.sh"));
        var builder = new BashShell(record.bash()).script(record.project())
                .redirectInput(script.toFile()).redirectErrorStream(true);
        builder.environment().put("DEVPULSE_DAEMON_PID",
                Long.toString(record.owner().pid()));
        builder.environment().put("DEVPULSE_RUN_ID", record.runId());
        builder.environment().put("DEVPULSE_TASK_NAME", record.name());
        builder.redirectOutput(log == null ? ProcessBuilder.Redirect.DISCARD
                : ProcessBuilder.Redirect.appendTo(log.toFile()));
        var process = builder.start();
        try {
            process.getOutputStream().close();
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)
                    ? process.exitValue() : -1;
        } finally {
            terminate(process.toHandle());
        }
    }

    private static void terminate(ProcessHandle process) {
        var descendants = process.descendants().toList().reversed();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        var interrupted = Thread.interrupted();
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        try {
            while ((process.isAlive()
                    || descendants.stream().anyMatch(ProcessHandle::isAlive))
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Path file(Path directory, String taskId) {
        return directory.resolve("daemons").resolve(taskId + ".conf");
    }

    private static void script(Path file, String command) throws IOException {
        Files.writeString(file, command.replace("\r\n", "\n") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void save(Path directory, Record record)
            throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("version", 1);
        data.put("runId", record.runId());
        data.put("taskId", record.taskId());
        data.put("name", record.name());
        data.put("project", record.project().toString());
        data.put("bash", record.bash().toString());
        data.put("pid", record.owner().pid());
        data.put("processStartedAt", record.owner().startedAt().toString());
        var ready = record.configuration().ready();
        data.put("readyCommand", ready.command());
        data.put("intervalNanos", ready.interval().toNanos());
        data.put("timeoutNanos", ready.timeout().toNanos());
        data.put("checkTimeoutNanos", ready.checkTimeout().toNanos());
        data.put("shutdownHook", record.configuration().shutdownHook());
        data.put("stopTimeoutNanos",
                record.configuration().stopTimeout().toNanos());
        if (record.configuration().stopCommand() != null) {
            data.put("stopCommand", record.configuration().stopCommand());
        }
        if (record.readyAt() != null) {
            data.put("readyAt", record.readyAt().toString());
        }
        if (record.stoppedAt() != null) {
            data.put("stoppedAt", record.stoppedAt().toString());
        }
        RunHistory.writeData(file(directory, record.taskId()), data);
    }

    private static Record read(Path directory, String taskId)
            throws IOException {
        var path = file(directory, taskId);
        try {
            var config = RunHistory.readConfig(path);
            if (config.getInt("version") != 1
                    || !taskId.equals(config.getString("taskId"))
                    || !directory.getFileName().toString()
                            .equals(config.getString("runId"))
                    || config.getLong("pid") <= 0) {
                throw new IOException("Invalid daemon metadata: " + path);
            }
            var ready = new PulseConfiguration.Readiness(
                    config.getString("readyCommand"),
                    Duration.ofNanos(config.getLong("intervalNanos")),
                    Duration.ofNanos(config.getLong("timeoutNanos")),
                    Duration.ofNanos(config.getLong("checkTimeoutNanos")));
            var daemon = new PulseConfiguration.Daemon(ready,
                    config.getBoolean("shutdownHook"),
                    config.hasPath("stopCommand")
                            ? config.getString("stopCommand") : null,
                    Duration.ofNanos(config.getLong("stopTimeoutNanos")));
            if (ready.interval().isNegative() || ready.interval().isZero()
                    || ready.timeout().isNegative()
                    || ready.timeout().isZero()
                    || ready.checkTimeout().isNegative()
                    || ready.checkTimeout().isZero()
                    || daemon.stopTimeout().isNegative()
                    || daemon.stopTimeout().isZero()) {
                throw new IOException("Invalid daemon durations: " + path);
            }
            return new Record(directory, config.getString("runId"), taskId,
                    config.getString("name"),
                    Path.of(config.getString("project")),
                    Path.of(config.getString("bash")),
                    new Owner(config.getLong("pid"),
                            Instant.parse(config.getString(
                                    "processStartedAt"))),
                    daemon, config.hasPath("readyAt")
                            ? Instant.parse(config.getString("readyAt")) : null,
                    config.hasPath("stoppedAt")
                            ? Instant.parse(config.getString("stoppedAt"))
                            : null);
        } catch (ConfigException | IllegalArgumentException
                | java.time.DateTimeException exception) {
            throw new IOException("Cannot read daemon state " + path + ": "
                    + exception.getMessage(), exception);
        }
    }
}
