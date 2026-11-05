package io.github.devpulse.core.execution;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigIncluderClasspath;
import com.typesafe.config.ConfigIncluderFile;
import com.typesafe.config.ConfigIncluderURL;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValueFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RunHistory {
    public enum State {
        PENDING, RUNNING, READY, SUCCESS, FAILED, SKIPPED, CANCELLED, INTERRUPTED
    }

    public record TaskState(String id, String name, List<String> needs,
            State state, Instant startedAt, Instant finishedAt,
            Integer exitCode, String error) {
        public TaskState {
            needs = List.copyOf(needs);
        }

        public String logPath() {
            return "logs/" + id + ".log";
        }

        private TaskState finish(State result, Instant time, Integer code,
                String reason) {
            return new TaskState(id, name, needs, result, startedAt, time,
                    code, reason);
        }
    }

    public record Snapshot(String id, String target, String project,
            int parallelism, long executorPid, Instant executorStartedAt,
            Instant startedAt, Instant finishedAt, State state,
            List<TaskState> tasks) {
        public Snapshot {
            tasks = List.copyOf(tasks);
        }
    }

    public record Observation(Snapshot snapshot, String warning) {}

    private final Path project;
    private final Path runs;

    public RunHistory(Path directory) throws IOException {
        project = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) {
            throw new IOException("Project directory does not exist: "
                    + project);
        }
        runs = project.resolve(".devpulse").resolve("runs");
    }

    public Writer begin(String target, TaskPlan plan, int parallelism)
            throws IOException {
        var id = UUID.randomUUID().toString();
        var directory = runs.resolve(id);
        Files.createDirectories(directory.resolve("logs"));
        var channel = FileChannel.open(directory.resolve("run.lock"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.lock();
            var owner = ProcessHandle.current();
            var tasks = plan.tasks().stream().map(task -> new TaskState(
                    UUID.randomUUID().toString(), task.name(), task.needs(),
                    State.PENDING, null, null, null, null)).toList();
            var snapshot = new Snapshot(id, target, project.toString(),
                    parallelism, owner.pid(),
                    owner.info().startInstant().orElse(null), Instant.now(),
                    null, State.RUNNING, tasks);
            var writer = new Writer(directory, channel, lock, snapshot);
            writer.save();
            return writer;
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    public List<Observation> list() throws IOException {
        var result = new ArrayList<Observation>();
        if (!Files.exists(runs)) {
            return List.of();
        }
        try (var directories = Files.newDirectoryStream(runs)) {
            for (var directory : directories) {
                if (Files.isDirectory(directory)
                        && validId(directory.getFileName().toString())
                        && Files.isRegularFile(
                        directory.resolve("state.conf"))) {
                    result.add(observe(directory));
                }
            }
        }
        result.sort(Comparator.comparing(
                (Observation item) -> item.snapshot().startedAt())
                .thenComparing(item -> item.snapshot().id()).reversed());
        return List.copyOf(result);
    }

    public Observation get(String id) throws IOException {
        if (!validId(id)) {
            throw new IOException("Invalid run ID: " + id);
        }
        var directory = runs.resolve(id);
        if (!Files.isRegularFile(directory.resolve("state.conf"))) {
            throw new IOException("Run not found: " + id);
        }
        return observe(directory);
    }

    public Path logFile(Snapshot snapshot, TaskState task) {
        return runs.resolve(snapshot.id()).resolve("logs")
                .resolve(task.id() + ".log");
    }

    public Path runDirectory(Snapshot snapshot) {
        return runs.resolve(snapshot.id());
    }

    private Observation observe(Path directory) throws IOException {
        var snapshot = read(directory);
        if (snapshot.state() != State.RUNNING) {
            return new Observation(snapshot, null);
        }
        var recovering = false;
        try (var channel = FileChannel.open(directory.resolve("run.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) {
                    return new Observation(snapshot, null);
                }
                snapshot = read(directory);
                if (snapshot.state() != State.RUNNING) {
                    return new Observation(snapshot, null);
                }
                var owner = ProcessHandle.of(snapshot.executorPid());
                if (owner.isPresent() && owner.get().isAlive()) {
                    var started = owner.get().info().startInstant();
                    if (snapshot.executorStartedAt() == null
                            || started.isEmpty()
                            || started.get().equals(
                            snapshot.executorStartedAt())) {
                        return new Observation(snapshot,
                                "Executor may still be alive without its lock."
                                        + " The result is uncertain.");
                    }
                }
                var interrupted = finish(snapshot, State.INTERRUPTED);
                recovering = true;
                write(directory, interrupted);
                return new Observation(interrupted,
                        "Recovered an unfinished run after executor exit.");
            } catch (OverlappingFileLockException exception) {
                return new Observation(snapshot, null);
            }
        } catch (IOException exception) {
            if (recovering) {
                throw new IOException("Could not persist recovered run "
                        + snapshot.id() + ": " + exception.getMessage(),
                        exception);
            }
            return new Observation(snapshot,
                    "Could not verify or recover this run: "
                            + exception.getMessage());
        }
    }

    private static Snapshot finish(Snapshot snapshot, State result) {
        var now = Instant.now();
        var tasks = snapshot.tasks().stream().map(task -> {
            if (task.state() == State.PENDING) {
                return task.finish(State.SKIPPED, now, null,
                        "Task was not started.");
            }
            if (task.state() == State.RUNNING) {
                var state = result == State.CANCELLED
                        ? State.CANCELLED : State.INTERRUPTED;
                return task.finish(state, now, null,
                        "Execution ended without a confirmed task result.");
            }
            return task;
        }).toList();
        return new Snapshot(snapshot.id(), snapshot.target(),
                snapshot.project(), snapshot.parallelism(),
                snapshot.executorPid(), snapshot.executorStartedAt(),
                snapshot.startedAt(), now, result, tasks);
    }

    private static Snapshot read(Path directory) throws IOException {
        var file = directory.resolve("state.conf");
        try {
            var config = readConfig(file);
            if (config.getInt("version") != 1
                    || !directory.getFileName().toString()
                    .equals(config.getString("id"))) {
                throw new IOException("Invalid run state: " + file);
            }
            var tasks = new ArrayList<TaskState>();
            for (var task : config.getConfigList("tasks")) {
                var id = task.getString("id");
                if (!validId(id)
                        || !("logs/" + id + ".log")
                        .equals(task.getString("logPath"))) {
                    throw new IOException("Invalid task log path: " + file);
                }
                tasks.add(new TaskState(id, task.getString("name"),
                        task.getStringList("needs"),
                        State.valueOf(task.getString("state")),
                        time(task, "startedAt"), time(task, "finishedAt"),
                        task.hasPath("exitCode")
                                ? task.getInt("exitCode") : null,
                        task.hasPath("error")
                                ? task.getString("error") : null));
            }
            var pid = config.getLong("executorPid");
            var parallelism = config.getInt("parallelism");
            var state = State.valueOf(config.getString("state"));
            var ended = time(config, "finishedAt");
            if (pid <= 0 || parallelism <= 0
                    || state == State.PENDING || state == State.SKIPPED
                    || state == State.READY
                    || (state != State.RUNNING && ended == null)) {
                throw new IOException("Invalid run metadata: " + file);
            }
            return new Snapshot(config.getString("id"),
                    config.getString("target"), config.getString("project"),
                    parallelism, pid,
                    time(config, "executorStartedAt"),
                    Instant.parse(config.getString("startedAt")),
                    ended, state, tasks);
        } catch (ConfigException | IllegalArgumentException
                | DateTimeException exception) {
            throw new IOException("Cannot read run state " + file + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static Instant time(Config config, String key) {
        return config.hasPath(key)
                ? Instant.parse(config.getString(key)) : null;
    }

    private static void write(Path directory, Snapshot snapshot)
            throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("version", 1);
        data.put("id", snapshot.id());
        data.put("target", snapshot.target());
        data.put("project", snapshot.project());
        data.put("parallelism", snapshot.parallelism());
        data.put("executorPid", snapshot.executorPid());
        put(data, "executorStartedAt", snapshot.executorStartedAt());
        data.put("startedAt", snapshot.startedAt().toString());
        put(data, "finishedAt", snapshot.finishedAt());
        data.put("state", snapshot.state().name());
        data.put("tasks", snapshot.tasks().stream().map(task -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", task.id());
            item.put("name", task.name());
            item.put("needs", task.needs());
            item.put("state", task.state().name());
            item.put("logPath", task.logPath());
            put(item, "startedAt", task.startedAt());
            put(item, "finishedAt", task.finishedAt());
            put(item, "exitCode", task.exitCode());
            put(item, "error", task.error());
            return item;
        }).toList());
        writeData(directory.resolve("state.conf"), data);
    }

    static Config readConfig(Path file) throws IOException {
        return ConfigFactory.parseString(
                Files.readString(file, StandardCharsets.UTF_8),
                ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF)
                        .setOriginDescription(file.toString())
                        .setAllowMissing(false).setIncluder(new NoIncludes()))
                .resolve(ConfigResolveOptions.noSystem());
    }

    static void writeData(Path file, Map<String, Object> data)
            throws IOException {
        var text = ConfigValueFactory.fromMap(data).render(
                ConfigRenderOptions.defaults().setJson(false)
                        .setComments(false).setOriginComments(false)) + "\n";
        var temporary = Files.createTempFile(file.getParent(), "state-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                var bytes = ByteBuffer.wrap(
                        text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            replaceSnapshot(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replaceSnapshot(Path temporary, Path destination)
            throws IOException {
        for (var attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException exception) {
                // Windows readers can briefly block atomic replacement.
                if (attempt >= 10) {
                    throw exception;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("State replacement interrupted.",
                            interrupted);
                }
            }
        }
    }

    private static void put(Map<String, Object> data, String key,
            Object value) {
        if (value != null) {
            data.put(key, value instanceof Instant ? value.toString() : value);
        }
    }

    private static boolean validId(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static final class Writer implements AutoCloseable {
        private final Path directory;
        private final FileChannel channel;
        private final FileLock lock;
        private Snapshot snapshot;

        private Writer(Path directory, FileChannel channel, FileLock lock,
                Snapshot snapshot) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
            this.snapshot = snapshot;
        }

        public String id() {
            return snapshot.id();
        }

        public Path directory() {
            return directory;
        }

        public synchronized String taskId(String name) {
            return task(name).id();
        }

        public synchronized void started(String name) throws IOException {
            if (snapshot.state() != State.RUNNING) {
                throw new IOException("Run has already ended.");
            }
            var task = task(name);
            update(new TaskState(task.id(), name, task.needs(), State.RUNNING,
                    Instant.now(), null, null, null));
            save();
        }

        public synchronized BufferedWriter openLog(String name)
                throws IOException {
            return Files.newBufferedWriter(
                    directory.resolve(task(name).logPath()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }

        public synchronized void completed(String name, State state,
                Integer exitCode, String error, Instant time)
                throws IOException {
            if (snapshot.state() == State.RUNNING) {
                update(task(name).finish(state, time, exitCode, error));
                save();
            }
        }

        public synchronized void ended(State state) throws IOException {
            if (snapshot.state() == State.RUNNING) {
                snapshot = finish(snapshot, state);
                save();
            }
        }

        private TaskState task(String name) {
            return snapshot.tasks().stream()
                    .filter(task -> task.name().equals(name))
                    .findFirst().orElseThrow();
        }

        private void update(TaskState replacement) {
            var tasks = snapshot.tasks().stream().map(task ->
                    task.name().equals(replacement.name())
                            ? replacement : task).toList();
            snapshot = new Snapshot(snapshot.id(), snapshot.target(),
                    snapshot.project(), snapshot.parallelism(),
                    snapshot.executorPid(), snapshot.executorStartedAt(),
                    snapshot.startedAt(), snapshot.finishedAt(),
                    snapshot.state(), tasks);
        }

        private void save() throws IOException {
            write(directory, snapshot);
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private static final class NoIncludes implements ConfigIncluder,
            ConfigIncluderFile, ConfigIncluderClasspath, ConfigIncluderURL {
        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            return this;
        }

        @Override
        public ConfigObject include(ConfigIncludeContext context,
                String name) {
            throw denied();
        }

        @Override
        public ConfigObject includeFile(ConfigIncludeContext context,
                File file) {
            throw denied();
        }

        @Override
        public ConfigObject includeResources(ConfigIncludeContext context,
                String name) {
            throw denied();
        }

        @Override
        public ConfigObject includeURL(ConfigIncludeContext context, URL url) {
            throw denied();
        }

        private static ConfigException denied() {
            return new ConfigException.Generic(
                    "Includes are not allowed in generated run state.");
        }
    }
}
