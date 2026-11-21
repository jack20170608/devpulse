package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.RunKey;
import io.github.devpulse.storage.RunRecord;
import io.github.devpulse.storage.jdbi.Bindings;
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
        return TableDescription.builder()
                .withName("run")
                .withEntityClass(RunRecord.class)
                .withIdField("id")
                .build();
    }

    /** Mark the run finished; only the two completion columns change. */
    public void finish(String runId, String state, Instant finishedAt) {
        jdbi.useHandle(handle -> {
            var update =
                    handle.createUpdate(
                            "UPDATE run SET state = :state, finished_at = :finishedAt "
                                    + "WHERE id = :id");
            Bindings.text(update, "state", state);
            Bindings.instant(update, "finishedAt", finishedAt);
            Bindings.text(update, "id", runId);
            update.execute();
        });
    }

    /** Runs of one project, newest first; a {@code null} project means every run. */
    public List<RunRecord> list(String project, int limit) {
        var params = new LinkedHashMap<String, Object>();
        params.put("limit", limit);
        if (project != null) {
            params.put("project", project);
        }
        return find(
                project == null
                        ? "SELECT * FROM run ORDER BY started_at DESC LIMIT :limit"
                        : "SELECT * FROM run WHERE project = :project "
                                + "ORDER BY started_at DESC LIMIT :limit",
                params,
                null);
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
