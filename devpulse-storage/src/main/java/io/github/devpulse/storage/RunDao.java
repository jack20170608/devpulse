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
 * DAO for the {@code run} table.
 *
 * <p>Demonstrates a single-column key that is not generated: the caller supplies the run id, so
 * {@link #create} returns the key read out of the entity rather than a database identity.
 */
public final class RunDao extends BaseDaoJdbiImpl<RunRecord, RunKey> {

    public RunDao(Jdbi jdbi) {
        super(description(), jdbi, run -> new RunKey(run.id()));
    }

    private static TableDescription description() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("id", "id");
        columns.put("project", "project");
        columns.put("target", "target");
        columns.put("parallelism", "parallelism");
        columns.put("state", "state");
        columns.put("executorPid", "executor_pid");
        columns.put("executorStartedAt", "executor_started_at");
        columns.put("startedAt", "started_at");
        columns.put("finishedAt", "finished_at");
        columns.put("bash", "bash");
        columns.put("toolVersion", "tool_version");
        columns.put("parentRunId", "parent_run_id");
        return TableDescription.builder()
                .withName("run")
                .withEntityClass(RunRecord.class)
                .withIdFields(List.of("id"))
                .withFieldColumnMap(columns)
                .build();
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(RunRecord.class, (result, context) -> map(result));
    }

    private static RunRecord map(ResultSet result) throws SQLException {
        return new RunRecord(
                result.getString("id"),
                result.getString("project"),
                result.getString("target"),
                result.getInt("parallelism"),
                result.getString("state"),
                result.getLong("executor_pid"),
                instant(result, "executor_started_at"),
                instant(result, "started_at"),
                instant(result, "finished_at"),
                result.getString("bash"),
                result.getString("tool_version"),
                result.getString("parent_run_id"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
