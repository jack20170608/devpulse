package io.github.devpulse.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.devpulse.storage.dao.RunDao;
import io.github.devpulse.storage.dao.TaskDao;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;
import org.jdbi.v3.core.statement.SqlStatement;

/**
 * A {@link RunStore} that composes the generated DAOs.
 *
 * <p>{@link RunDao} and {@link TaskDao} handle every write and read that a table description can
 * express. This class keeps only what a generic DAO cannot express: the {@code task_need} child
 * table, the {@code task_result} upsert, the run-finish update, and the filtered run listing.
 */
public final class JdbiRunStore implements RunStore {
    private static final String UPDATE_TASK_STATE =
            """
            UPDATE task SET
                state = :state,
                exit_code = :exitCode,
                error = :error,
                started_at = :startedAt,
                finished_at = :finishedAt,
                log_path = :logPath,
                reused_from = :reusedFrom,
                daemon_health = :daemonHealth,
                daemon_error = :daemonError,
                daemon_checked_at = :daemonCheckedAt
            WHERE run_id = :runId AND name = :name
            """;

    private static final String FINISH_RUN =
            "UPDATE run SET state = :state, finished_at = :finishedAt WHERE id = :id";

    private static final String LIST_RUNS =
            "SELECT * FROM run ORDER BY started_at DESC LIMIT :limit";

    private static final String LIST_PROJECT_RUNS =
            "SELECT * FROM run WHERE project = :project ORDER BY started_at DESC LIMIT :limit";

    private static final String LIST_TASKS =
            "SELECT * FROM task WHERE run_id = :runId ORDER BY sort_order, name";

    private static final String INSERT_NEED =
            """
            INSERT INTO task_need (run_id, task_name, need_name, sort_order)
            VALUES (:runId, :taskName, :needName, :sortOrder)
            """;

    private static final String SELECT_NEEDS =
            """
            SELECT task_name, need_name FROM task_need
            WHERE run_id = :runId ORDER BY task_name, sort_order
            """;

    private static final String SELECT_RESULT =
            """
            SELECT name, fingerprint, run_id, finished_at FROM task_result
            WHERE name = :name AND fingerprint = :fingerprint
            """;

    private static final String MERGE_RESULT =
            """
            MERGE INTO task_result (name, fingerprint, run_id, finished_at)
            KEY (name, fingerprint)
            VALUES (:name, :fingerprint, :runId, :finishedAt)
            """;

    private static final String MERGE_DAEMON =
            """
            MERGE INTO daemon_instance (
                run_id, task_id, instance_id, attempt, name, project, bash,
                pid, process_started_at, ready_command, stop_command, stop_timeout_nanos,
                interval_nanos, timeout_nanos, check_timeout_nanos, ready_at, stopped_at)
            KEY (run_id, task_id, instance_id)
            VALUES (
                :runId, :taskId, :instanceId, :attempt, :name, :project, :bash,
                :pid, :processStartedAt, :readyCommand, :stopCommand, :stopTimeoutNanos,
                :intervalNanos, :timeoutNanos, :checkTimeoutNanos, :readyAt, :stoppedAt)
            """;

    private static final String SELECT_DAEMONS =
            """
            SELECT * FROM daemon_instance
            WHERE run_id = :runId AND task_id = :taskId
            ORDER BY attempt, process_started_at, instance_id
            """;

    private final Jdbi jdbi;
    private final RunDao runDao;
    private final TaskDao taskDao;

    private JdbiRunStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        this.runDao = new RunDao(jdbi);
        this.taskDao = new TaskDao(jdbi);
    }

    /** Open or create a store backed by one H2 file. */
    public static JdbiRunStore open(Path file) throws IOException {
        var path = file.toAbsolutePath().toString().replace('\\', '/');
        return open("jdbc:h2:file:" + path);
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

    @Override
    public void migrate() throws IOException {
        Schema.migrate(jdbi);
    }

    @Override
    public int schemaVersion() throws IOException {
        return Schema.currentVersion(jdbi);
    }

    @Override
    public void begin(RunRecord run, List<TaskRecord> tasks) throws IOException {
        try {
            jdbi.useTransaction(handle -> {
                runDao.invokeCreate(run).withHandle(handle);
                for (var task : tasks) {
                    taskDao.invokeCreate(task).withHandle(handle);
                    insertNeeds(handle, task);
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
            jdbi.useHandle(handle -> {
                var update = handle.createUpdate(UPDATE_TASK_STATE);
                text(update, "runId", task.runId());
                text(update, "name", task.name());
                text(update, "state", task.state());
                number(update, "exitCode", task.exitCode());
                text(update, "error", task.error());
                instant(update, "startedAt", task.startedAt());
                instant(update, "finishedAt", task.finishedAt());
                text(update, "logPath", task.logPath());
                text(update, "reusedFrom", task.reusedFrom());
                text(update, "daemonHealth", task.daemonHealth());
                text(update, "daemonError", task.daemonError());
                instant(update, "daemonCheckedAt", task.daemonCheckedAt());
                update.execute();
            });
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void finishRun(String runId, String state, Instant finishedAt) throws IOException {
        try {
            jdbi.useHandle(handle -> {
                var update = handle.createUpdate(FINISH_RUN);
                text(update, "state", state);
                instant(update, "finishedAt", finishedAt);
                text(update, "id", runId);
                update.execute();
            });
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
    public List<RunRecord> runs(String project, int limit) throws IOException {
        var params = new LinkedHashMap<String, Object>();
        params.put("limit", limit);
        if (project != null) {
            params.put("project", project);
        }
        try {
            return runDao.find(project == null ? LIST_RUNS : LIST_PROJECT_RUNS, params, null);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public List<TaskRecord> tasks(String runId) throws IOException {
        try {
            var stored = taskDao.find(LIST_TASKS, Map.of("runId", runId), null);
            var needs = needs(runId);
            var completed = new ArrayList<TaskRecord>(stored.size());
            for (var task : stored) {
                completed.add(withNeeds(task, needs.getOrDefault(task.name(), List.of())));
            }
            return List.copyOf(completed);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public Optional<TaskResult> successfulResult(String name, String fingerprint)
            throws IOException {
        try {
            return jdbi.withHandle(handle ->
                    handle.createQuery(SELECT_RESULT)
                            .bind("name", name)
                            .bind("fingerprint", fingerprint)
                            .map((result, context) ->
                                    new TaskResult(
                                            result.getString("name"),
                                            result.getString("fingerprint"),
                                            result.getString("run_id"),
                                            instantValue(result, "finished_at")))
                            .findOne());
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void recordResult(TaskResult result) throws IOException {
        try {
            jdbi.useHandle(handle -> {
                var update = handle.createUpdate(MERGE_RESULT);
                text(update, "name", result.name());
                text(update, "fingerprint", result.fingerprint());
                text(update, "runId", result.runId());
                instant(update, "finishedAt", result.finishedAt());
                update.execute();
            });
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void recordDaemon(DaemonRecord record) throws IOException {
        try {
            jdbi.useHandle(handle -> {
                var update = handle.createUpdate(MERGE_DAEMON);
                text(update, "runId", record.runId());
                text(update, "taskId", record.taskId());
                text(update, "instanceId", record.instanceId());
                number(update, "attempt", record.attempt());
                text(update, "name", record.name());
                text(update, "project", record.project());
                text(update, "bash", record.bash());
                update.bind("pid", record.pid());
                instant(update, "processStartedAt", record.processStartedAt());
                text(update, "readyCommand", record.readyCommand());
                text(update, "stopCommand", record.stopCommand());
                update.bind("stopTimeoutNanos", record.stopTimeoutNanos());
                update.bind("intervalNanos", record.intervalNanos());
                update.bind("timeoutNanos", record.timeoutNanos());
                update.bind("checkTimeoutNanos", record.checkTimeoutNanos());
                instant(update, "readyAt", record.readyAt());
                instant(update, "stoppedAt", record.stoppedAt());
                update.execute();
            });
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public List<DaemonRecord> daemons(String runId, String taskId) throws IOException {
        try {
            return jdbi.withHandle(handle ->
                    handle.createQuery(SELECT_DAEMONS)
                            .bind("runId", runId)
                            .bind("taskId", taskId)
                            .map((result, context) -> daemonRecord(result))
                            .list());
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public void close() {
        // JDBI opens a connection per call, so nothing is held open to release.
    }

    private static void insertNeeds(Handle handle, TaskRecord task) {
        for (var index = 0; index < task.needs().size(); index++) {
            var insert = handle.createUpdate(INSERT_NEED);
            text(insert, "runId", task.runId());
            text(insert, "taskName", task.name());
            text(insert, "needName", task.needs().get(index));
            number(insert, "sortOrder", index);
            insert.execute();
        }
    }

    private Map<String, List<String>> needs(String runId) {
        return jdbi.withHandle(handle ->
                handle.createQuery(SELECT_NEEDS)
                        .bind("runId", runId)
                        .reduceRows(
                                new LinkedHashMap<String, List<String>>(),
                                (map, row) -> {
                                    map.computeIfAbsent(
                                                    row.getColumn("task_name", String.class),
                                                    key -> new ArrayList<>())
                                            .add(row.getColumn("need_name", String.class));
                                    return map;
                                }));
    }

    private static TaskRecord withNeeds(TaskRecord task, List<String> needs) {
        return new TaskRecord(
                task.runId(),
                task.name(),
                task.taskId(),
                task.fingerprint(),
                task.state(),
                task.exitCode(),
                task.error(),
                task.startedAt(),
                task.finishedAt(),
                task.logPath(),
                task.reusedFrom(),
                task.daemonHealth(),
                task.daemonError(),
                task.daemonCheckedAt(),
                task.sortOrder(),
                needs);
    }

    private static DaemonRecord daemonRecord(ResultSet result) throws SQLException {
        return new DaemonRecord(
                result.getString("run_id"),
                result.getString("task_id"),
                result.getString("instance_id"),
                result.getInt("attempt"),
                result.getString("name"),
                result.getString("project"),
                result.getString("bash"),
                result.getLong("pid"),
                instantValue(result, "process_started_at"),
                result.getString("ready_command"),
                result.getString("stop_command"),
                result.getLong("stop_timeout_nanos"),
                result.getLong("interval_nanos"),
                result.getLong("timeout_nanos"),
                result.getLong("check_timeout_nanos"),
                instantValue(result, "ready_at"),
                instantValue(result, "stopped_at"));
    }

    private static Instant instantValue(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void text(SqlStatement<?> statement, String name, String value) {
        if (value == null) {
            statement.bindNull(name, Types.VARCHAR);
        } else {
            statement.bind(name, value);
        }
    }

    private static void number(SqlStatement<?> statement, String name, Integer value) {
        if (value == null) {
            statement.bindNull(name, Types.INTEGER);
        } else {
            statement.bind(name, value);
        }
    }

    private static void instant(SqlStatement<?> statement, String name, Instant value) {
        if (value == null) {
            statement.bindNull(name, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.bind(name, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    private static IOException wrap(JdbiException exception) {
        return new IOException("Run store failure: " + exception.getMessage(), exception);
    }
}
