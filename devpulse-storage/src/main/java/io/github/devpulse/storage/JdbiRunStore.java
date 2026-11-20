package io.github.devpulse.storage;

import io.github.devpulse.storage.dao.DaemonInstanceDao;
import io.github.devpulse.storage.dao.RunDao;
import io.github.devpulse.storage.dao.TaskDao;
import io.github.devpulse.storage.dao.TaskNeedDao;
import io.github.devpulse.storage.dao.TaskResultDao;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

/**
 * A {@link RunStore} that composes one DAO per table.
 *
 * <p>Every statement lives with the table it belongs to; this class only orders the calls and
 * translates JDBI failures into {@link IOException}. The one thing worth reading here is {@link
 * #begin}, which inserts a run, its tasks and their dependency edges in a single transaction so a
 * failed start leaves no row behind.
 */
public final class JdbiRunStore implements RunStore {
    private final Jdbi jdbi;
    private final RunDao runDao;
    private final TaskDao taskDao;
    private final TaskNeedDao needDao;
    private final TaskResultDao resultDao;
    private final DaemonInstanceDao daemonDao;

    private JdbiRunStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        this.runDao = new RunDao(jdbi);
        this.taskDao = new TaskDao(jdbi);
        this.needDao = new TaskNeedDao(jdbi);
        this.resultDao = new TaskResultDao(jdbi);
        this.daemonDao = new DaemonInstanceDao(jdbi);
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
                    var needs = task.needs();
                    for (var index = 0; index < needs.size(); index++) {
                        needDao.invokeCreate(
                                        new TaskNeedRecord(
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
    public List<RunRecord> runs(String project, int limit) throws IOException {
        try {
            return runDao.list(project, limit);
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public List<TaskRecord> tasks(String runId) throws IOException {
        try {
            var needs = new HashMap<String, List<String>>();
            for (var need : needDao.byRun(runId)) {
                needs.computeIfAbsent(need.taskName(), name -> new ArrayList<>())
                        .add(need.needName());
            }
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
    public Optional<TaskResult> successfulResult(String name, String fingerprint)
            throws IOException {
        try {
            return resultDao.successful(name, fingerprint);
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
    public void close() {
        // JDBI holds no connection of its own; each call opens and closes one.
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

    private static IOException wrap(JdbiException exception) {
        return new IOException("Run store failure: " + exception.getMessage(), exception);
    }
}
