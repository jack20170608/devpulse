package io.github.devpulse.storage;

import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/**
 * DAO for the {@code task} table.
 *
 * <p>Demonstrates a composite key: {@code (run_id, name)}. The dependency edges live in {@code
 * task_need}, not in a column, so they are not part of this description and every row read here
 * reports an empty {@code needs} list; load them separately when the graph matters.
 */
public final class TaskDao extends BaseDaoJdbiImpl<TaskRecord, TaskKey> {

    public TaskDao(Jdbi jdbi) {
        super(description(), jdbi, task -> new TaskKey(task.runId(), task.name()));
    }

    private static TableDescription description() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("runId", "run_id");
        columns.put("name", "name");
        columns.put("taskId", "task_id");
        columns.put("fingerprint", "fingerprint");
        columns.put("state", "state");
        columns.put("exitCode", "exit_code");
        columns.put("error", "error");
        columns.put("startedAt", "started_at");
        columns.put("finishedAt", "finished_at");
        columns.put("logPath", "log_path");
        columns.put("reusedFrom", "reused_from");
        columns.put("daemonHealth", "daemon_health");
        columns.put("daemonError", "daemon_error");
        columns.put("daemonCheckedAt", "daemon_checked_at");
        columns.put("sortOrder", "sort_order");
        return TableDescription.builder()
                .withName("task")
                .withEntityClass(TaskRecord.class)
                .withIdFields(List.of("runId", "name"))
                .withFieldColumnMap(columns)
                .build();
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
                result.getString("fingerprint"),
                result.getString("state"),
                integer(result, "exit_code"),
                result.getString("error"),
                instant(result, "started_at"),
                instant(result, "finished_at"),
                result.getString("log_path"),
                result.getString("reused_from"),
                result.getString("daemon_health"),
                result.getString("daemon_error"),
                instant(result, "daemon_checked_at"),
                result.getInt("sort_order"),
                List.of());
    }

    private static Integer integer(ResultSet result, String column) throws SQLException {
        var value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
