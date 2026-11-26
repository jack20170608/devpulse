package io.github.devpulse.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.devpulse.storage.dao.DaemonInstanceDao;
import io.github.devpulse.storage.dao.ProjectDao;
import io.github.devpulse.storage.dao.RunDao;
import io.github.devpulse.storage.dao.TaskDao;
import io.github.devpulse.storage.dao.TaskEdgeDao;
import io.github.devpulse.storage.dao.TaskResultDao;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

/**
 * The {@link RunStore} implementation: one database, one DAO per table.
 *
 * <p>Every statement lives with the table it belongs to; this class only orders the calls and
 * translates JDBI failures into {@link IOException}. The method worth reading is {@link #begin},
 * which writes the project, the run, its tasks and their edges in one transaction so a failed start
 * leaves no row behind.
 */
public final class JdbiRunStore implements RunStore {

    private final Jdbi jdbi;
    private final ProjectDao projectDao;
    private final RunDao runDao;
    private final TaskDao taskDao;
    private final TaskEdgeDao edgeDao;
    private final TaskResultDao resultDao;
    private final DaemonInstanceDao daemonDao;

    /** Set when this store owns a connection pool that {@link #close()} must release. */
    private final AutoCloseable closeable;

    private JdbiRunStore(Jdbi jdbi) {
        this(jdbi, null);
    }

    private JdbiRunStore(Jdbi jdbi, AutoCloseable closeable) {
        this.jdbi = jdbi;
        this.closeable = closeable;
        this.projectDao = new ProjectDao(jdbi);
        this.runDao = new RunDao(jdbi);
        this.taskDao = new TaskDao(jdbi);
        this.edgeDao = new TaskEdgeDao(jdbi);
        this.resultDao = new TaskResultDao(jdbi);
        this.daemonDao = new DaemonInstanceDao(jdbi);
    }

    /** Open or create a store backed by one H2 file. */
    public static JdbiRunStore open(Path file) throws IOException {
        return open("jdbc:h2:file:" + file.toAbsolutePath().toString().replace('\\', '/'));
    }

    /**
     * Open a store by JDBC URL.
     *
     * <p>An in-memory H2 URL must include {@code DB_CLOSE_DELAY=-1}: JDBI uses a connection per
     * call, so without it the database would disappear between calls.
     */
    public static JdbiRunStore open(String jdbcUrl) throws IOException {
        return new JdbiRunStore(Jdbi.create(jdbcUrl, "sa", ""));
    }

    /** Connections the pool keeps for a long-running caller. */
    private static final int POOL_SIZE = 4;

    /**
     * Open a store behind a small connection pool.
     *
     * <p>A CLI command opens the store once and exits, so a pool would only add threads it never
     * uses. A server does the opposite: without a pool every query opens a new H2 connection, which
     * serialises badly under concurrent requests. Callers of this factory must close the store to
     * release the pool.
     */
    public static JdbiRunStore pooled(String jdbcUrl) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(POOL_SIZE);
        config.setPoolName("devpulse-store");
        var dataSource = new HikariDataSource(config);
        return new JdbiRunStore(Jdbi.create(dataSource), dataSource);
    }

    /** Pooled variant of {@link #open(Path)}. */
    public static JdbiRunStore pooled(Path file) {
        return pooled("jdbc:h2:file:" + file.toAbsolutePath().toString().replace('\\', '/'));
    }

    /**
     * The read side for listings and dashboards.
     *
     * <p>Shares this store's connections but not its types: {@link RunQueries} returns {@link
     * RunSummary}, so a list column never forces a change to {@link RunRecord}.
     */
    public RunQueries queries() {
        return new JdbiRunQueries(jdbi);
    }

    @Override
    public void migrate() throws IOException {
        Schema.migrate(jdbi);
    }

    @Override
    public int schemaVersion() throws IOException {
        return Schema.currentVersion(jdbi);
    }

    @Override
    public void begin(ProjectRecord project, RunRecord run, List<TaskRecord> tasks)
            throws IOException {
        try {
            jdbi.useTransaction(handle -> {
                projectDao.upsert(handle, project);
                runDao.invokeCreate(run).withHandle(handle);
                for (var task : tasks) {
                    taskDao.invokeCreate(task).withHandle(handle);
                }
                // Edges last: both endpoints must exist, and the table enforces that.
                for (var task : tasks) {
                    var needs = task.needs();
                    for (var index = 0; index < needs.size(); index++) {
                        edgeDao.invokeCreate(
                                        new TaskEdgeRecord(
                                                task.runId(), task.name(), needs.get(index), index))
                                .withHandle(handle);
                    }
                }
            });
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void updateTask(TaskRecord task) throws IOException {
        try {
            taskDao.update(new TaskKey(task.runId(), task.name()), task);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void updateTaskState(TaskRecord task) throws IOException {
        try {
            taskDao.updateState(task);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void finishRun(String runId, String state, Instant finishedAt) throws IOException {
        try {
            runDao.finish(runId, state, finishedAt);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public Optional<RunRecord> run(String runId) throws IOException {
        try {
            return runDao.findOne(new RunKey(runId));
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public List<RunRecord> runs(String projectId, int limit) throws IOException {
        try {
            return runDao.list(projectId, limit);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public Optional<ProjectRecord> project(String id) throws IOException {
        try {
            return projectDao.find(id);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public List<TaskRecord> tasks(String runId) throws IOException {
        try {
            var needs = needsByTask(edgeDao.byRun(runId));
            var result = new ArrayList<TaskRecord>();
            for (var task : taskDao.listByRun(runId)) {
                result.add(withNeeds(task, needs.getOrDefault(task.name(), List.of())));
            }
            return List.copyOf(result);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public Map<String, List<TaskRecord>> tasks(List<String> runIds) throws IOException {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        try {
            var needsByRun = new HashMap<String, Map<String, List<String>>>();
            for (var edge : edgeDao.byRuns(runIds)) {
                needsByRun
                        .computeIfAbsent(edge.runId(), id -> new HashMap<>())
                        .computeIfAbsent(edge.dependentName(), name -> new ArrayList<>())
                        .add(edge.prerequisiteName());
            }
            var grouped = new LinkedHashMap<String, List<TaskRecord>>();
            for (var task : taskDao.listByRuns(runIds)) {
                var needs =
                        needsByRun
                                .getOrDefault(task.runId(), Map.of())
                                .getOrDefault(task.name(), List.of());
                grouped.computeIfAbsent(task.runId(), id -> new ArrayList<>())
                        .add(withNeeds(task, needs));
            }
            var result = new LinkedHashMap<String, List<TaskRecord>>();
            for (var entry : grouped.entrySet()) {
                result.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(result);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public Optional<TaskResult> successfulResult(String projectId, String name, String fingerprint)
            throws IOException {
        try {
            return resultDao.successful(projectId, name, fingerprint);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void recordResult(TaskResult result) throws IOException {
        try {
            resultDao.merge(result);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void recordDaemon(DaemonRecord record) throws IOException {
        try {
            daemonDao.merge(record);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public List<DaemonRecord> daemons(String runId, String taskId) throws IOException {
        try {
            return daemonDao.byTask(runId, taskId);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public Map<String, List<DaemonRecord>> daemons(String runId) throws IOException {
        try {
            return daemonDao.byRun(runId);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void recordDaemonHealth(
            String runId, String taskId, String health, String error, Instant checkedAt)
            throws IOException {
        try {
            daemonDao.updateHealth(runId, taskId, health, error, checkedAt);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void close() throws IOException {
        if (closeable == null) {
            // JDBI holds no connection of its own; each call opens and closes one.
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            throw new IOException(
                    "Cannot close the run store: " + exception.getMessage(), exception);
        }
    }

    private static Map<String, List<String>> needsByTask(List<TaskEdgeRecord> edges) {
        var needs = new HashMap<String, List<String>>();
        for (var edge : edges) {
            needs.computeIfAbsent(edge.dependentName(), name -> new ArrayList<>())
                    .add(edge.prerequisiteName());
        }
        return needs;
    }

    private static TaskRecord withNeeds(TaskRecord task, List<String> needs) {
        return new TaskRecord(
                task.runId(),
                task.name(),
                task.taskId(),
                task.seq(),
                task.fingerprint(),
                task.state(),
                task.exitCode(),
                task.error(),
                task.errorKind(),
                task.startedAt(),
                task.finishedAt(),
                task.logPath(),
                task.reusedFrom(),
                needs);
    }

    private static IOException wrap(JdbiException exception) {
        return new IOException("Run store failure: " + exception.getMessage(), exception);
    }
}
