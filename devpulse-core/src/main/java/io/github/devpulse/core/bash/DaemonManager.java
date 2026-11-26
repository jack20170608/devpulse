package io.github.devpulse.core.bash;

import io.github.devpulse.core.dag.DaemonOptions;
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.storage.DaemonRecord;
import io.github.devpulse.storage.JdbiRunStore;
import io.github.devpulse.storage.RunStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DaemonManager {
    private enum Identity {
        MATCH,
        GONE,
        UNKNOWN
    }

    public record Observation(String state, long pid, String detail) {}

    public record Instance(String id, int attempt, Observation observation) {}

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
            return current.get().equals(startedAt) ? Identity.MATCH : Identity.GONE;
        }
    }

    private record Record(
            Path directory,
            String runId,
            String taskId,
            String instanceId,
            int attempt,
            String name,
            Path project,
            Path bash,
            Owner owner,
            BashTaskDefinition.Daemon configuration,
            DaemonOptions options,
            Instant readyAt,
            Instant stoppedAt) {
        Record ready() {
            return new Record(
                    directory,
                    runId,
                    taskId,
                    instanceId,
                    attempt,
                    name,
                    project,
                    bash,
                    owner,
                    configuration,
                    options,
                    Instant.now(),
                    stoppedAt);
        }

        Record stopped() {
            return new Record(
                    directory,
                    runId,
                    taskId,
                    instanceId,
                    attempt,
                    name,
                    project,
                    bash,
                    owner,
                    configuration,
                    options,
                    readyAt,
                    Instant.now());
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
    private final String runId;
    private final Path directory;
    private final ConcurrentHashMap<String, String> taskIds = new ConcurrentHashMap<>();

    public DaemonManager(RunHistory.Writer history, BashShell bash, Path project) {
        this.history = history;
        this.bash = Objects.requireNonNull(bash, "bash");
        this.project = Objects.requireNonNull(project, "project").toAbsolutePath().normalize();
        runId = history == null ? UUID.randomUUID().toString() : history.id();
        directory =
                history == null
                        ? this.project.resolve(".devpulse").resolve("daemons").resolve(runId)
                        : history.directory();
    }

    public DaemonManager(BashShell bash, Path project) {
        this(null, bash, project);
    }

    public DagDaemon.Handle<ProcessHandle> start(BashTaskDefinition task) throws IOException {
        return start(task, DaemonOptions.DEFAULT, 1);
    }

    public DagDaemon.Handle<ProcessHandle> start(BashTaskDefinition task, DaemonOptions options)
            throws IOException {
        return start(task, options, 1);
    }

    DagDaemon.Handle<ProcessHandle> start(
            BashTaskDefinition task, DaemonOptions options, int attempt) throws IOException {
        Objects.requireNonNull(options, "options");
        var id =
                history == null
                        ? taskIds.computeIfAbsent(
                                task.name(), ignored -> UUID.randomUUID().toString())
                        : history.taskId(task.name());
        var instanceId = UUID.randomUUID().toString();
        var files = directory.resolve("daemons").resolve(id);
        Files.createDirectories(files);
        var log = directory.resolve("logs").resolve(id + ".log");
        Files.createDirectories(log.getParent());
        try (var ignored =
                Files.newOutputStream(
                        log,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {}
        var start = files.resolve(instanceId + "-start.sh");
        script(start, task.command());
        script(files.resolve(instanceId + "-ready.sh"), task.daemon().readyCommand());
        if (task.daemon().stopCommand() != null) {
            script(files.resolve(instanceId + "-stop.sh"), task.daemon().stopCommand());
        }
        var process =
                bash.script(project)
                        .redirectInput(start.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
        try {
            process.getOutputStream().close();
            var startedAt =
                    process.info()
                            .startInstant()
                            .orElseThrow(() ->
                                    new IOException("Cannot record daemon process identity."));
            var record =
                    new Record(
                            directory,
                            runId,
                            id,
                            instanceId,
                            attempt,
                            task.name(),
                            project,
                            bash.executable(),
                            new Owner(process.pid(), startedAt),
                            task.daemon(),
                            options,
                            null,
                            null);
            save(directory, record);
            var daemon = new Managed(process, record);
            return new DagDaemon.Handle<>() {
                @Override
                public ProcessHandle value() {
                    return process.toHandle();
                }

                @Override
                public DagDaemon.Health check() throws IOException, InterruptedException {
                    if (!process.isAlive()) {
                        return DagDaemon.Health.EXITED;
                    }
                    var code =
                            DaemonManager.check(
                                    daemon.record, daemon.record.options().checkTimeout(), null);
                    var alreadyReady = daemon.record.readyAt() != null;
                    var health = healthAfterCheck(process.isAlive(), code, alreadyReady);
                    if (health == DagDaemon.Health.READY && !alreadyReady) {
                        daemon.record = daemon.record.ready();
                        save(directory, daemon.record);
                    }
                    return health;
                }
            };
        } catch (IOException | RuntimeException exception) {
            terminate(process.toHandle());
            throw exception;
        }
    }

    public static boolean recorded(Path directory, String taskId) throws IOException {
        return !records(directory, taskId).isEmpty();
    }

    public static List<Instance> instances(Path directory, String taskId)
            throws IOException, InterruptedException {
        var result = new ArrayList<Instance>();
        for (var record : records(directory, taskId)) {
            result.add(new Instance(record.instanceId(), record.attempt(), observe(record)));
        }
        return List.copyOf(result);
    }

    /**
     * Every recorded daemon instance of one run, grouped by task id.
     *
     * <p>A per-task lookup opens and migrates the run store each time, so asking for several tasks
     * meant paying that cost repeatedly. This reads the store once, which keeps the cost of {@code
     * status} from growing with the number of daemon tasks.
     */
    public static Map<String, List<Instance>> instances(Path directory, Collection<String> taskIds)
            throws IOException, InterruptedException {
        var runId = directory.getFileName().toString();
        var grouped = new LinkedHashMap<String, List<Instance>>();
        try (var store = storeForRunDirectory(directory)) {
            for (var taskId : taskIds) {
                var found = new ArrayList<Instance>();
                for (var record : store.daemons(runId, taskId)) {
                    var converted = toRecord(directory, record);
                    found.add(
                            new Instance(
                                    converted.instanceId(),
                                    converted.attempt(),
                                    observe(converted)));
                }
                if (!found.isEmpty()) {
                    grouped.put(taskId, List.copyOf(found));
                }
            }
        }
        return grouped;
    }

    private static Observation observe(Record record) throws InterruptedException {
        return switch (record.owner().identity()) {
            case UNKNOWN ->
                    new Observation(
                            "UNKNOWN",
                            record.owner().pid(),
                            "Process identity cannot be confirmed.");
            case GONE ->
                    new Observation(
                            record.stoppedAt() == null ? "EXITED" : "STOPPED",
                            record.owner().pid(),
                            null);
            case MATCH -> {
                try {
                    var code = check(record, record.options().checkTimeout(), null);
                    var identity = record.owner().identity();
                    if (identity == Identity.UNKNOWN) {
                        yield new Observation(
                                "UNKNOWN",
                                record.owner().pid(),
                                "Process identity cannot be confirmed.");
                    }
                    if (identity == Identity.GONE) {
                        yield new Observation(
                                "EXITED",
                                record.owner().pid(),
                                "Process exited during the readiness check.");
                    }
                    yield new Observation(
                            code == 0
                                    ? "READY"
                                    : record.readyAt() == null ? "STARTING" : "NOT_READY",
                            record.owner().pid(),
                            code == 0 ? null : "Readiness check failed or timed out.");
                } catch (IOException exception) {
                    yield new Observation(
                            "UNKNOWN",
                            record.owner().pid(),
                            "Cannot check readiness: " + exception.getMessage());
                }
            }
        };
    }

    public static void stop(Path directory, String taskId)
            throws IOException, InterruptedException {
        var instances = records(directory, taskId);
        if (instances.isEmpty()) {
            throw new IOException("No daemon process was recorded.");
        }
        IOException failure = null;
        for (var record : instances.reversed()) {
            try {
                stop(record);
            } catch (IOException exception) {
                var error =
                        new IOException(
                                "Instance " + record.instanceId() + ": " + exception.getMessage(),
                                exception);
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static void stop(Path directory, String taskId, String instanceId)
            throws IOException, InterruptedException {
        var record =
                records(directory, taskId).stream()
                        .filter(value -> value.instanceId().equals(instanceId))
                        .findFirst()
                        .orElseThrow(() ->
                                new IOException("Instance not found for this task: " + instanceId));
        stop(record);
    }

    private static void stop(Record selected) throws IOException, InterruptedException {
        var directory = selected.directory();
        var taskId = selected.taskId();
        var lockFile =
                instanceDirectory(directory, taskId).resolve(selected.instanceId() + ".lock");
        try (var channel =
                        FileChannel.open(
                                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Daemon cleanup is already in progress.");
            }
            var record = find(directory, taskId, selected.instanceId());
            var identity = record.owner().identity();
            if (identity == Identity.UNKNOWN) {
                throw new IOException("Refusing to stop an unconfirmed process identity.");
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
            handle.descendants()
                    .toList()
                    .reversed()
                    .forEach(child ->
                            child.info()
                                    .startInstant()
                                    .ifPresent(time -> owners.add(new Owner(child.pid(), time))));
            owners.add(record.owner());
            identity = record.owner().identity();
            if (identity == Identity.GONE) {
                save(directory, record.stopped());
                return;
            }
            if (identity == Identity.UNKNOWN) {
                throw new IOException("Refusing to stop an unconfirmed process identity.");
            }
            var deadline = System.nanoTime() + record.configuration().stopTimeout().toNanos();
            IOException stopFailure = null;
            var command = record.configuration().stopCommand();
            if (command != null) {
                try {
                    var code =
                            check(
                                    record,
                                    record.configuration().stopTimeout(),
                                    directory.resolve("logs").resolve(taskId + ".log"));
                    if (code != 0) {
                        stopFailure =
                                new IOException(
                                        code == -1
                                                ? "Daemon stop command timed out."
                                                : "Daemon stop command exited with code "
                                                        + code
                                                        + ".");
                    }
                } catch (IOException exception) {
                    stopFailure = exception;
                }
            } else {
                signal(owners, false);
            }
            awaitExit(owners, deadline);
            signal(owners, true);
            awaitExit(owners, System.nanoTime() + Duration.ofSeconds(2).toNanos());
            if (owners.stream().anyMatch(owner -> owner.identity() != Identity.GONE)) {
                throw new IOException("Daemon process cleanup could not be confirmed.");
            }
            save(directory, record.stopped());
            if (stopFailure != null) {
                throw stopFailure;
            }
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Daemon cleanup is already in progress.", exception);
        }
    }

    private static void awaitExit(List<Owner> owners, long deadline) throws InterruptedException {
        while (owners.stream().anyMatch(owner -> owner.identity() != Identity.GONE)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void signal(List<Owner> owners, boolean force) {
        for (var owner : owners) {
            if (owner.identity() == Identity.MATCH) {
                ProcessHandle.of(owner.pid())
                        .ifPresent(process -> {
                            if (force) {
                                process.destroyForcibly();
                            } else {
                                process.destroy();
                            }
                        });
            }
        }
    }

    /**
     * Decide daemon health from the outcome of a readiness check.
     *
     * <p>{@link DagDaemon.Health#EXITED} wins over a zero exit code, because a process that died
     * during its own readiness check is not ready. A zero exit code means ready regardless of the
     * earlier state. A non-zero exit code stays {@link DagDaemon.Health#STARTING} until the daemon
     * has been ready at least once, and is {@link DagDaemon.Health#UNAVAILABLE} afterwards.
     */
    static DagDaemon.Health healthAfterCheck(boolean alive, int checkCode, boolean alreadyReady) {
        if (!alive) {
            return DagDaemon.Health.EXITED;
        }
        if (checkCode == 0) {
            return DagDaemon.Health.READY;
        }
        return alreadyReady ? DagDaemon.Health.UNAVAILABLE : DagDaemon.Health.STARTING;
    }

    private static int check(Record record, Duration timeout, Path log)
            throws IOException, InterruptedException {
        var script =
                record.directory()
                        .resolve("daemons")
                        .resolve(record.taskId())
                        .resolve(record.instanceId() + (log == null ? "-ready.sh" : "-stop.sh"));
        if (record.instanceId().equals(record.taskId())) {
            script =
                    record.directory()
                            .resolve("daemons")
                            .resolve(record.taskId() + (log == null ? "-ready.sh" : "-stop.sh"));
        }
        var builder =
                new BashShell(record.bash())
                        .script(projectOf(record.directory()))
                        .redirectInput(script.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("DEVPULSE_DAEMON_PID", Long.toString(record.owner().pid()));
        builder.environment().put("DEVPULSE_RUN_ID", record.runId());
        builder.environment().put("DEVPULSE_TASK_NAME", record.name());
        builder.environment().put("DEVPULSE_DAEMON_INSTANCE_ID", record.instanceId());
        builder.redirectOutput(
                log == null
                        ? ProcessBuilder.Redirect.DISCARD
                        : ProcessBuilder.Redirect.appendTo(log.toFile()));
        var process = builder.start();
        try {
            process.getOutputStream().close();
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)
                    ? process.exitValue()
                    : -1;
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
            while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
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

    private static Path instanceDirectory(Path directory, String taskId) {
        validateId(taskId);
        return directory.resolve("daemons").resolve(taskId);
    }

    /** A run directory is {@code <project>/.devpulse/runs/<runId>}. */
    private static Path projectOf(Path runDirectory) {
        return runDirectory.getParent().getParent().getParent();
    }

    private static void validateId(String id) {
        if (!UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid daemon ID: " + id);
        }
    }

    private static List<Record> records(Path directory, String taskId) throws IOException {
        var runId = directory.getFileName().toString();
        var result = new ArrayList<Record>();
        for (var record : storeForRunDirectory(directory).daemons(runId, taskId)) {
            result.add(toRecord(directory, record));
        }
        return result;
    }

    /** Resolve the store from a run directory: {@code <project>/.devpulse/runs/<runId>}. */
    private static RunStore storeForRunDirectory(Path directory) throws IOException {
        return storeForProject(directory.getParent().getParent().getParent());
    }

    private static RunStore storeForProject(Path project) throws IOException {
        var store = JdbiRunStore.open(project.resolve(".devpulse").resolve("state"));
        store.migrate();
        return store;
    }

    private static DaemonRecord toDaemonRecord(Record record) {
        return new DaemonRecord(
                record.runId(),
                record.taskId(),
                record.instanceId(),
                record.attempt(),
                record.name(),
                record.bash().toString(),
                record.owner().pid(),
                record.owner().startedAt(),
                record.configuration().readyCommand(),
                record.configuration().stopCommand(),
                record.configuration().stopTimeout().toNanos(),
                record.options().checkInterval().toNanos(),
                record.options().readyTimeout().toNanos(),
                record.options().checkTimeout().toNanos(),
                null,
                null,
                null,
                record.readyAt(),
                record.stoppedAt(),
                null);
    }

    private static Record toRecord(Path directory, DaemonRecord record) {
        return new Record(
                directory,
                record.runId(),
                record.taskId(),
                record.instanceId(),
                record.attempt(),
                record.name(),
                projectOf(directory),
                Path.of(record.bash()),
                new Owner(record.pid(), record.processStartedAt()),
                new BashTaskDefinition.Daemon(
                        record.readyCommand(),
                        record.stopCommand(),
                        Duration.ofNanos(record.stopTimeoutNanos())),
                new DaemonOptions(
                        Duration.ofNanos(record.timeoutNanos()),
                        Duration.ofNanos(record.intervalNanos()),
                        Duration.ofNanos(record.checkTimeoutNanos())),
                record.readyAt(),
                record.stoppedAt());
    }

    private static Record find(Path directory, String taskId, String instanceId)
            throws IOException {
        return records(directory, taskId).stream()
                .filter(value -> value.instanceId().equals(instanceId))
                .findFirst()
                .orElseThrow(
                        () -> new IOException("Instance not found for this task: " + instanceId));
    }

    private static void script(Path file, String command) throws IOException {
        Files.writeString(
                file,
                command.replace("\r\n", "\n") + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void save(Path directory, Record record) throws IOException {
        storeForProject(projectOf(record.directory())).recordDaemon(toDaemonRecord(record));
    }
}
