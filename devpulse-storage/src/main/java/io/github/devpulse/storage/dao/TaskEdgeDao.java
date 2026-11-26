package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.TaskEdgeKey;
import io.github.devpulse.storage.TaskEdgeRecord;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

/** DAO for the {@code task_edge} table: one row per dependency edge. */
public final class TaskEdgeDao extends BaseDaoJdbiImpl<TaskEdgeRecord, TaskEdgeKey> {

    public TaskEdgeDao(Jdbi jdbi) {
        super(description(), jdbi, TaskEdgeDao::key);
    }

    private static TaskEdgeKey key(TaskEdgeRecord edge) {
        return new TaskEdgeKey(edge.runId(), edge.dependentName(), edge.prerequisiteName());
    }

    private static TableDescription description() {
        return TableDescription.builder()
                .withName("task_edge")
                .withEntityClass(TaskEdgeRecord.class)
                .withIdFields(List.of("runId", "dependentName", "prerequisiteName"))
                .build();
    }

    /** Every edge of one run, ordered by the dependent then its declaration order. */
    public List<TaskEdgeRecord> byRun(String runId) {
        return find(
                "SELECT * FROM task_edge WHERE run_id = :runId "
                        + "ORDER BY dependent_name, seq, prerequisite_name",
                Map.of("runId", runId),
                null);
    }

    /** Every edge of several runs, ordered so they group back per run and dependent. */
    public List<TaskEdgeRecord> byRuns(List<String> runIds) {
        return find(
                "SELECT * FROM task_edge WHERE run_id IN (<runIds>) "
                        + "ORDER BY run_id, dependent_name, seq, prerequisite_name",
                Map.of(),
                Map.of("runIds", runIds));
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(TaskEdgeRecord.class, (result, context) -> map(result));
    }

    private static TaskEdgeRecord map(ResultSet result) throws SQLException {
        return new TaskEdgeRecord(
                result.getString("run_id"),
                result.getString("dependent_name"),
                result.getString("prerequisite_name"),
                result.getInt("seq"));
    }
}
