package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.WorkflowRevisionKey;
import io.github.devpulse.storage.WorkflowRevisionRecord;
import io.github.devpulse.storage.jdbi.Bindings;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** DAO for the immutable {@code workflow_revision} history. */
public final class WorkflowRevisionDao
        extends BaseDaoJdbiImpl<WorkflowRevisionRecord, WorkflowRevisionKey> {

    public WorkflowRevisionDao(Jdbi jdbi) {
        super(
                description(),
                jdbi,
                revision ->
                        new WorkflowRevisionKey(
                                revision.projectId(), revision.workflowId(), revision.revision()));
    }

    private static TableDescription description() {
        return TableDescription.builder()
                .withName("workflow_revision")
                .withEntityClass(WorkflowRevisionRecord.class)
                .withIdFields(List.of("projectId", "workflowId", "revision"))
                .build();
    }

    /** Newest revisions first. */
    public List<WorkflowRevisionRecord> list(String projectId, String workflowId, int limit) {
        var params = new LinkedHashMap<String, Object>();
        params.put("projectId", projectId);
        params.put("workflowId", workflowId);
        params.put("limit", limit);
        return find(
                "SELECT * FROM workflow_revision "
                        + "WHERE project_id = :projectId AND workflow_id = :workflowId "
                        + "ORDER BY revision DESC LIMIT :limit",
                params,
                null);
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(WorkflowRevisionRecord.class, (result, context) -> map(result));
    }

    private static WorkflowRevisionRecord map(ResultSet result) throws SQLException {
        return new WorkflowRevisionRecord(
                result.getString("project_id"),
                result.getString("workflow_id"),
                result.getInt("revision"),
                result.getString("content"),
                result.getString("content_hash"),
                Bindings.instant(result, "created_at"));
    }
}
