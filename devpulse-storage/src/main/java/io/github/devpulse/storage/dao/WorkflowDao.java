package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.WorkflowKey;
import io.github.devpulse.storage.WorkflowRecord;
import io.github.devpulse.storage.jdbi.Bindings;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** DAO for the {@code workflow} head table. */
public final class WorkflowDao extends BaseDaoJdbiImpl<WorkflowRecord, WorkflowKey> {

    public WorkflowDao(Jdbi jdbi) {
        super(
                description(),
                jdbi,
                workflow -> new WorkflowKey(workflow.projectId(), workflow.workflowId()));
    }

    private static TableDescription description() {
        return TableDescription.builder()
                .withName("workflow")
                .withEntityClass(WorkflowRecord.class)
                .withIdFields(List.of("projectId", "workflowId"))
                .build();
    }

    /** Workflows of one project, by id. */
    public List<WorkflowRecord> listByProject(String projectId) {
        var params = new LinkedHashMap<String, Object>();
        params.put("projectId", projectId);
        return find(
                "SELECT * FROM workflow WHERE project_id = :projectId ORDER BY workflow_id",
                params,
                null);
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(WorkflowRecord.class, (result, context) -> map(result));
    }

    private static WorkflowRecord map(ResultSet result) throws SQLException {
        return new WorkflowRecord(
                result.getString("project_id"),
                result.getString("workflow_id"),
                result.getString("name"),
                result.getString("content"),
                result.getString("content_hash"),
                result.getInt("revision"),
                Bindings.instant(result, "created_at"),
                Bindings.instant(result, "updated_at"));
    }
}
