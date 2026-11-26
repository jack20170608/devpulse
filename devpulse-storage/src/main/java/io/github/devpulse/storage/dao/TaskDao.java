package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.TaskKey;
import io.github.devpulse.storage.TaskRecord;
import io.github.devpulse.storage.jdbi.Bindings;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

/**
 * DAO for the {@code task} table.
 *
 * <p>Dependency edges live in {@code task_edge}, not in a column, so every row read here reports an
 * empty {@code needs} list; load them separately when the graph matters.
 */
public final class TaskDao extends BaseDaoJdbiImpl<TaskRecord, TaskKey> {

    public TaskDao(Jdbi jdbi) {
        super(description(), jdbi, task -> new TaskKey(task.runId(), task.name()));
    }

    private static TableDescription description() {
        return TableDescription.builder()
                .withName("task")
                .withEntityClass(TaskRecord.class)
                .withIdFields(List.of("runId", "name"))
                .withoutField("needs")
                .build();
    }

    /** Update only the mutable execution state of a task. */
    public void updateState(TaskRecord task) {
        jdbi.useHandle(handle -> {
            var update =
                    handle.createUpdate(
                            "UPDATE task SET state = :state, exit_code = :exitCode, "
                                    + "error = :error, error_kind = :errorKind, "
                                    + "started_at = :startedAt, finished_at = :finishedAt, "
                                    + "log_path = :logPath, reused_from = :reusedFrom "
                                    + "WHERE run_id = :runId AND name = :name");
            Bindings.text(update, "runId", task.runId());
            Bindings.text(update, "name", task.name());
            Bindings.text(update, "state", task.state());
            Bindings.number(update, "exitCode", task.exitCode());
            Bindings.text(update, "error", task.error());
            Bindings.text(update, "errorKind", task.errorKind());
            Bindings.instant(update, "startedAt", task.startedAt());
            Bindings.instant(update, "finishedAt", task.finishedAt());
            Bindings.text(update, "logPath", task.logPath());
            Bindings.text(update, "reusedFrom", task.reusedFrom());
            update.execute();
        });
    }

    /** Every task of one run, in the order the run recorded them. */
    public List<TaskRecord> listByRun(String runId) {
        return find(
                "SELECT * FROM task WHERE run_id = :runId ORDER BY seq, name",
                Map.of("runId", runId),
                null);
    }

    /** Every task of several runs, ordered so each run reads back in its recorded order. */
    public List<TaskRecord> listByRuns(List<String> runIds) {
        return find(
                "SELECT * FROM task WHERE run_id IN (<runIds>) ORDER BY run_id, seq, name",
                Map.of(),
                Map.of("runIds", runIds));
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(TaskRecord.class, (result, context) -> map(result));
    }

    private static TaskRecord map(ResultSet result) throws SQLException {
        return new TaskRecord(
                result.getString("run_id"),
                result.getString("name"),
                result.getString("task_id"),
                result.getInt("seq"),
                result.getString("fingerprint"),
                result.getString("state"),
                Bindings.integer(result, "exit_code"),
                result.getString("error"),
                result.getString("error_kind"),
                Bindings.instant(result, "started_at"),
                Bindings.instant(result, "finished_at"),
                result.getString("log_path"),
                result.getString("reused_from"),
                List.of());
    }
}
