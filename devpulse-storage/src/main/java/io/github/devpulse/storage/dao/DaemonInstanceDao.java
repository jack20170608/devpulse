package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.domain.DaemonKey;
import io.github.devpulse.storage.DaemonRecord;
import io.github.devpulse.storage.jdbi.Bindings;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

/** DAO for the {@code daemon_instance} table. */
public final class DaemonInstanceDao extends BaseDaoJdbiImpl<DaemonRecord, DaemonKey> {
    private static final String MERGE =
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

    private static final String BY_TASK =
            """
            SELECT * FROM daemon_instance
            WHERE run_id = :runId AND task_id = :taskId
            ORDER BY attempt, process_started_at, instance_id
            """;

    public DaemonInstanceDao(Jdbi jdbi) {
        super(description(), jdbi, record -> key(record));
    }

    private static DaemonKey key(DaemonRecord record) {
        return new DaemonKey(record.runId(), record.taskId(), record.instanceId());
    }

    private static TableDescription description() {
        return TableDescription.builder()
                .withName("daemon_instance")
                .withEntityClass(DaemonRecord.class)
                .withIdFields(List.of("runId", "taskId", "instanceId"))
                .build();
    }

    /** Insert or replace one instance, so a readiness or stop observation updates its row. */
    public void merge(DaemonRecord record) {
        jdbi.useHandle(handle -> {
            var update = handle.createUpdate(MERGE);
            Bindings.text(update, "runId", record.runId());
            Bindings.text(update, "taskId", record.taskId());
            Bindings.text(update, "instanceId", record.instanceId());
            Bindings.number(update, "attempt", record.attempt());
            Bindings.text(update, "name", record.name());
            Bindings.text(update, "project", record.project());
            Bindings.text(update, "bash", record.bash());
            update.bind("pid", record.pid());
            Bindings.instant(update, "processStartedAt", record.processStartedAt());
            Bindings.text(update, "readyCommand", record.readyCommand());
            Bindings.text(update, "stopCommand", record.stopCommand());
            update.bind("stopTimeoutNanos", record.stopTimeoutNanos());
            update.bind("intervalNanos", record.intervalNanos());
            update.bind("timeoutNanos", record.timeoutNanos());
            update.bind("checkTimeoutNanos", record.checkTimeoutNanos());
            Bindings.instant(update, "readyAt", record.readyAt());
            Bindings.instant(update, "stoppedAt", record.stoppedAt());
            update.execute();
        });
    }

    /** One run's instances of a task, oldest attempt first. */
    public List<DaemonRecord> byTask(String runId, String taskId) {
        return find(BY_TASK, Map.of("runId", runId, "taskId", taskId), null);
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(DaemonRecord.class, (result, context) -> map(result));
    }

    private static DaemonRecord map(ResultSet result) throws SQLException {
        return new DaemonRecord(
                result.getString("run_id"),
                result.getString("task_id"),
                result.getString("instance_id"),
                result.getInt("attempt"),
                result.getString("name"),
                result.getString("project"),
                result.getString("bash"),
                result.getLong("pid"),
                Bindings.instant(result, "process_started_at"),
                result.getString("ready_command"),
                result.getString("stop_command"),
                result.getLong("stop_timeout_nanos"),
                result.getLong("interval_nanos"),
                result.getLong("timeout_nanos"),
                result.getLong("check_timeout_nanos"),
                Bindings.instant(result, "ready_at"),
                Bindings.instant(result, "stopped_at"));
    }
}
