package io.github.devpulse.core.bash;

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
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagGraph;
import io.github.devpulse.storage.JdbiRunStore;
import io.github.devpulse.storage.RunRecord;
import io.github.devpulse.storage.RunStore;
import io.github.devpulse.storage.TaskRecord;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RunHistory {
    /** Immutable run inputs, written once at start; execution state lives in the run database. */
    private static final String PLAN_FILE = "plan.conf";

    public enum State {
        PENDING,
        RUNNING,
        READY,
        SUCCESS,
        FAILED,
        SKIPPED,
        CANCELLED,
        INTERRUPTED
    }

    public record TaskState(
            String id,
            String name,
            List<String> needs,
            State state,
            Instant startedAt,
            Instant finishedAt,
            Integer exitCode,
            String error,
            DagDaemon.Health daemonHealth,
            String daemonError,
            Instant daemonCheckedAt) {
        public TaskState {
            needs = List.copyOf(needs);
        }

        public String logPath() {
            return "logs/" + id + ".log";
        }

        private TaskState finish(State result, Instant time, Integer code, String reason) {
            return new TaskState(
                    id,
                    name,
                    needs,
                    result,
                    startedAt,
                    time,
                    code == null ? exitCode : code,
                    reason,
                    daemonHealth,
                    daemonError,
                    daemonCheckedAt);
        }
    }

    public record Snapshot(
            String id,
            String target,
            String project,
            int parallelism,
            long executorPid,
            Instant executorStartedAt,
            Instant startedAt,
            Instant finishedAt,
            State state,
            List<TaskState> tasks) {
        public Snapshot {
            tasks = List.copyOf(tasks);
        }
    }

    public record Observation(Snapshot snapshot, String warning) {}

    private final Path project;
    private final Path runs;
    private RunStore store;

    public RunHistory(Path directory) throws IOException {
        project = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) {
            throw new IOException("Project directory does not exist: " + project);
        }
        runs = project.resolve(".devpulse").resolve("runs");
    }

    /** Open the run store on first use, so read-only commands never create one. */
    private synchronized RunStore store() throws IOException {
        if (store == null) {
            var opened = JdbiRunStore.open(project.resolve(".devpulse").resolve("state"));
            opened.migrate();
            store = opened;
        }
        return store;
    }

    public Writer begin(String target, DagGraph graph, int parallelism) throws IOException {
        return begin(target, graph, parallelism, null);
    }

    /**
     * Start a run and record its immutable manifest.
     *
     * <p>The manifest is written once, before any task runs, so a later retry compares
     * configuration fingerprints against a stable record instead of against mutable execution
     * state.
     */
    public Writer begin(String target, DagGraph graph, int parallelism, PlanManifest manifest)
            throws IOException {
        var id = UUID.randomUUID().toString();
        var directory = runs.resolve(id);
        Files.createDirectories(directory.resolve("logs"));
        var channel =
                FileChannel.open(
                        directory.resolve("run.lock"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.lock();
            var owner = ProcessHandle.current();
            var tasks =
                    graph.topologicalOrder().stream()
                            .map(key ->
                                    new TaskState(
                                            UUID.randomUUID().toString(),
                                            key.id(),
                                            graph.node(key).needs().stream()
                                                    .map(dependency -> dependency.id())
                                                    .toList(),
                                            State.PENDING,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null))
                            .toList();
            var snapshot =
                    new Snapshot(
                            id,
                            target,
                            project.toString(),
                            parallelism,
                            owner.pid(),
                            owner.info().startInstant().orElse(null),
                            Instant.now(),
                            null,
                            State.RUNNING,
                            tasks);
            var fingerprints = new HashMap<String, String>();
            if (manifest != null) {
                manifest.nodes()
                        .forEach((name, node) -> fingerprints.put(name, node.fingerprint()));
            }
            var opened = store();
            opened.begin(runRecord(snapshot, manifest), taskRecords(snapshot, manifest));
            var writer = new Writer(directory, channel, lock, snapshot, opened, fingerprints);
            if (manifest != null) {
                writePlan(directory, id, manifest);
            }
            return writer;
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    public List<Observation> list() throws IOException {
        var result = new ArrayList<Observation>();
        for (var run : store().runs(null, Integer.MAX_VALUE)) {
            result.add(observe(run.id()));
        }
        return List.copyOf(result);
    }

    /**
     * Read a run's immutable manifest.
     *
     * @throws IOException when the run has no manifest, which makes it ineligible for a retry
     */
    public PlanManifest plan(String id) throws IOException {
        if (!validId(id)) {
            throw new IOException("Invalid run ID: " + id);
        }
        var file = runs.resolve(id).resolve(PLAN_FILE);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Run " + id + " has no plan manifest and cannot be retried.");
        }
        try {
            var config = readConfig(file);
            if (config.getInt("version") != 1) {
                throw new IOException("Unsupported plan manifest version in " + file);
            }
            var nodes = new LinkedHashMap<String, PlanManifest.Node>();
            for (var item : config.getConfigList("nodes")) {
                var name = item.getString("name");
                nodes.put(
                        name,
                        new PlanManifest.Node(
                                name, item.getString("fingerprint"), item.getStringList("needs")));
            }
            return new PlanManifest(
                    config.getString("target"),
                    config.getString("project"),
                    config.getInt("parallelism"),
                    config.hasPath("bash") ? config.getString("bash") : null,
                    config.hasPath("toolVersion") ? config.getString("toolVersion") : null,
                    config.hasPath("parentRunId") ? config.getString("parentRunId") : null,
                    nodes);
        } catch (ConfigException exception) {
            throw new IOException(
                    "Cannot read plan manifest " + file + ": " + exception.getMessage(), exception);
        }
    }

    public Observation get(String id) throws IOException {
        if (!validId(id)) {
            throw new IOException("Invalid run ID: " + id);
        }
        if (store().run(id).isEmpty()) {
            throw new IOException("Run not found: " + id);
        }
        return observe(id);
    }

    public Path logFile(Snapshot snapshot, TaskState task) {
        return runs.resolve(snapshot.id()).resolve("logs").resolve(task.id() + ".log");
    }

    public Path runDirectory(Snapshot snapshot) {
        return runs.resolve(snapshot.id());
    }

    private Observation observe(String id) throws IOException {
        var directory = runs.resolve(id);
        var snapshot = read(id);
        if (snapshot.state() != State.RUNNING) {
            return new Observation(snapshot, null);
        }
        var recovering = false;
        try (var channel =
                FileChannel.open(
                        directory.resolve("run.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) {
                    return new Observation(snapshot, null);
                }
                snapshot = read(id);
                if (snapshot.state() != State.RUNNING) {
                    return new Observation(snapshot, null);
                }
                var owner = ProcessHandle.of(snapshot.executorPid());
                if (owner.isPresent() && owner.get().isAlive()) {
                    var started = owner.get().info().startInstant();
                    if (snapshot.executorStartedAt() == null
                            || started.isEmpty()
                            || started.get().equals(snapshot.executorStartedAt())) {
                        return new Observation(
                                snapshot,
                                "Executor may still be alive without its lock."
                                        + " The result is uncertain.");
                    }
                }
                var interrupted = finish(snapshot, State.INTERRUPTED);
                recovering = true;
                persist(interrupted);
                return new Observation(
                        interrupted, "Recovered an unfinished run after executor exit.");
            } catch (OverlappingFileLockException exception) {
                return new Observation(snapshot, null);
            }
        } catch (IOException exception) {
            if (recovering) {
                throw new IOException(
                        "Could not persist recovered run "
                                + snapshot.id()
                                + ": "
                                + exception.getMessage(),
                        exception);
            }
            return new Observation(
                    snapshot, "Could not verify or recover this run: " + exception.getMessage());
        }
    }

    private static RunRecord runRecord(Snapshot snapshot, PlanManifest manifest) {
        return new RunRecord(
                snapshot.id(),
                snapshot.project(),
                snapshot.target(),
                snapshot.parallelism(),
                snapshot.state().name(),
                snapshot.executorPid(),
                snapshot.executorStartedAt(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                manifest == null ? null : manifest.bash(),
                manifest == null ? null : manifest.toolVersion(),
                manifest == null ? null : manifest.parentRunId());
    }

    private static List<TaskRecord> taskRecords(Snapshot snapshot, PlanManifest manifest) {
        var result = new ArrayList<TaskRecord>(snapshot.tasks().size());
        for (var index = 0; index < snapshot.tasks().size(); index++) {
            var task = snapshot.tasks().get(index);
            var node = manifest == null ? null : manifest.nodes().get(task.name());
            result.add(
                    new TaskRecord(
                            snapshot.id(),
                            task.name(),
                            task.id(),
                            node == null ? null : node.fingerprint(),
                            task.state().name(),
                            task.exitCode(),
                            task.error(),
                            task.startedAt(),
                            task.finishedAt(),
                            task.logPath(),
                            null,
                            task.daemonHealth() == null ? null : task.daemonHealth().name(),
                            task.daemonError(),
                            task.daemonCheckedAt(),
                            index,
                            task.needs()));
        }
        return result;
    }

    private static TaskRecord taskRecord(
            Snapshot snapshot, TaskState task, Map<String, String> fingerprints) {
        return new TaskRecord(
                snapshot.id(),
                task.name(),
                task.id(),
                fingerprints.get(task.name()),
                task.state().name(),
                task.exitCode(),
                task.error(),
                task.startedAt(),
                task.finishedAt(),
                task.logPath(),
                null,
                task.daemonHealth() == null ? null : task.daemonHealth().name(),
                task.daemonError(),
                task.daemonCheckedAt(),
                snapshot.tasks().indexOf(task),
                task.needs());
    }

    /** Persist a whole snapshot, used when recovery rewrites several tasks at once. */
    private void persist(Snapshot snapshot) throws IOException {
        var opened = store();
        opened.finishRun(snapshot.id(), snapshot.state().name(), snapshot.finishedAt());
        for (var task : snapshot.tasks()) {
            opened.updateTaskState(taskRecord(snapshot, task, Map.of()));
        }
    }

    private Snapshot read(String id) throws IOException {
        var run = store().run(id).orElseThrow(() -> new IOException("Run not found: " + id));
        var tasks = new ArrayList<TaskState>();
        for (var task : store().tasks(id)) {
            tasks.add(
                    new TaskState(
                            task.taskId(),
                            task.name(),
                            task.needs(),
                            State.valueOf(task.state()),
                            task.startedAt(),
                            task.finishedAt(),
                            task.exitCode(),
                            task.error(),
                            task.daemonHealth() == null
                                    ? null
                                    : DagDaemon.Health.valueOf(task.daemonHealth()),
                            task.daemonError(),
                            task.daemonCheckedAt()));
        }
        return new Snapshot(
                run.id(),
                run.target(),
                run.project(),
                run.parallelism(),
                run.executorPid(),
                run.executorStartedAt(),
                run.startedAt(),
                run.finishedAt(),
                State.valueOf(run.state()),
                tasks);
    }

    private static Snapshot finish(Snapshot snapshot, State result) {
        var now = Instant.now();
        var tasks =
                snapshot.tasks().stream()
                        .map(task -> {
                            if (task.state() == State.PENDING) {
                                return task.finish(
                                        State.SKIPPED, now, null, "Task was not started.");
                            }
                            if (task.state() == State.RUNNING) {
                                var state =
                                        result == State.CANCELLED
                                                ? State.CANCELLED
                                                : State.INTERRUPTED;
                                return task.finish(
                                        state,
                                        now,
                                        null,
                                        "Execution ended without a confirmed task result.");
                            }
                            return task;
                        })
                        .toList();
        return new Snapshot(
                snapshot.id(),
                snapshot.target(),
                snapshot.project(),
                snapshot.parallelism(),
                snapshot.executorPid(),
                snapshot.executorStartedAt(),
                snapshot.startedAt(),
                now,
                result,
                tasks);
    }

    private static void writePlan(Path directory, String runId, PlanManifest manifest)
            throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("version", 1);
        data.put("runId", runId);
        data.put("target", manifest.target());
        data.put("project", manifest.project());
        data.put("parallelism", manifest.parallelism());
        put(data, "bash", manifest.bash());
        put(data, "toolVersion", manifest.toolVersion());
        put(data, "parentRunId", manifest.parentRunId());
        var nodes = new ArrayList<Map<String, Object>>();
        for (var node : manifest.nodes().values()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("name", node.name());
            item.put("fingerprint", node.fingerprint());
            item.put("needs", node.needs());
            nodes.add(item);
        }
        data.put("nodes", nodes);
        writeData(directory.resolve(PLAN_FILE), data);
    }

    static Config readConfig(Path file) throws IOException {
        return ConfigFactory.parseString(
                        Files.readString(file, StandardCharsets.UTF_8),
                        ConfigParseOptions.defaults()
                                .setSyntax(ConfigSyntax.CONF)
                                .setOriginDescription(file.toString())
                                .setAllowMissing(false)
                                .setIncluder(new NoIncludes()))
                .resolve(ConfigResolveOptions.noSystem());
    }

    static void writeData(Path file, Map<String, Object> data) throws IOException {
        var text =
                ConfigValueFactory.fromMap(data)
                                .render(
                                        ConfigRenderOptions.defaults()
                                                .setJson(false)
                                                .setComments(false)
                                                .setOriginComments(false))
                        + "\n";
        var temporary = Files.createTempFile(file.getParent(), "state-", ".tmp");
        try {
            try (var channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                var bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
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

    private static void replaceSnapshot(Path temporary, Path destination) throws IOException {
        for (var attempt = 0; ; attempt++) {
            try {
                Files.move(
                        temporary,
                        destination,
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
                    throw new IOException("State replacement interrupted.", interrupted);
                }
            }
        }
    }

    private static void put(Map<String, Object> data, String key, Object value) {
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
        private final RunStore store;
        private final Map<String, String> fingerprints;
        private Snapshot snapshot;

        private Writer(
                Path directory,
                FileChannel channel,
                FileLock lock,
                Snapshot snapshot,
                RunStore store,
                Map<String, String> fingerprints) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
            this.snapshot = snapshot;
            this.store = store;
            this.fingerprints = fingerprints;
        }

        public String id() {
            return snapshot.id();
        }

        /** The store backing this run, so a caller can consult the cross-run reuse index. */
        public RunStore store() {
            return store;
        }

        /** The current in-memory state of the run. */
        public Snapshot snapshot() {
            return snapshot;
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
            if (task.state() == State.RUNNING) {
                return;
            }
            update(
                    new TaskState(
                            task.id(),
                            name,
                            task.needs(),
                            State.RUNNING,
                            Instant.now(),
                            null,
                            null,
                            null,
                            task.daemonHealth(),
                            task.daemonError(),
                            task.daemonCheckedAt()));
            saveTask(name);
        }

        public synchronized BufferedWriter openLog(String name) throws IOException {
            return openLog(name, false);
        }

        synchronized BufferedWriter openLog(String name, boolean append) throws IOException {
            return Files.newBufferedWriter(
                    directory.resolve(task(name).logPath()),
                    StandardCharsets.UTF_8,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }

        public synchronized void processExited(String name, int exitCode) throws IOException {
            if (snapshot.state() == State.RUNNING) {
                var task = task(name);
                update(
                        new TaskState(
                                task.id(),
                                name,
                                task.needs(),
                                task.state(),
                                task.startedAt(),
                                task.finishedAt(),
                                exitCode,
                                task.error(),
                                task.daemonHealth(),
                                task.daemonError(),
                                task.daemonCheckedAt()));
                saveTask(name);
            }
        }

        public synchronized void completed(
                String name, State state, Integer exitCode, String error, Instant time)
                throws IOException {
            if (snapshot.state() == State.RUNNING) {
                update(task(name).finish(state, time, exitCode, error));
                saveTask(name);
            }
        }

        public synchronized void ended(State state) throws IOException {
            if (snapshot.state() == State.RUNNING) {
                snapshot = finish(snapshot, state);
                save();
            }
        }

        public synchronized void daemonChecked(
                String name, DagDaemon.Health health, String error, Instant time)
                throws IOException {
            if (snapshot.state() == State.RUNNING) {
                var task = task(name);
                update(
                        new TaskState(
                                task.id(),
                                name,
                                task.needs(),
                                task.state(),
                                task.startedAt(),
                                task.finishedAt(),
                                task.exitCode(),
                                task.error(),
                                health,
                                error,
                                time));
                saveTask(name);
            }
        }

        private TaskState task(String name) {
            return snapshot.tasks().stream()
                    .filter(task -> task.name().equals(name))
                    .findFirst()
                    .orElseThrow();
        }

        private void update(TaskState replacement) {
            var tasks =
                    snapshot.tasks().stream()
                            .map(task ->
                                    task.name().equals(replacement.name()) ? replacement : task)
                            .toList();
            snapshot =
                    new Snapshot(
                            snapshot.id(),
                            snapshot.target(),
                            snapshot.project(),
                            snapshot.parallelism(),
                            snapshot.executorPid(),
                            snapshot.executorStartedAt(),
                            snapshot.startedAt(),
                            snapshot.finishedAt(),
                            snapshot.state(),
                            tasks);
        }

        /** Persist one task after a state change; the other tasks are untouched. */
        private void saveTask(String name) throws IOException {
            store.updateTaskState(taskRecord(snapshot, task(name), fingerprints));
        }

        /** Persist the whole snapshot, used once when the run ends and several tasks change. */
        private void save() throws IOException {
            store.finishRun(snapshot.id(), snapshot.state().name(), snapshot.finishedAt());
            for (var task : snapshot.tasks()) {
                store.updateTaskState(taskRecord(snapshot, task, fingerprints));
            }
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

    private static final class NoIncludes
            implements ConfigIncluder,
                    ConfigIncluderFile,
                    ConfigIncluderClasspath,
                    ConfigIncluderURL {
        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            return this;
        }

        @Override
        public ConfigObject include(ConfigIncludeContext context, String name) {
            throw denied();
        }

        @Override
        public ConfigObject includeFile(ConfigIncludeContext context, File file) {
            throw denied();
        }

        @Override
        public ConfigObject includeResources(ConfigIncludeContext context, String name) {
            throw denied();
        }

        @Override
        public ConfigObject includeURL(ConfigIncludeContext context, URL url) {
            throw denied();
        }

        private static ConfigException denied() {
            return new ConfigException.Generic("Includes are not allowed in generated run state.");
        }
    }
}
