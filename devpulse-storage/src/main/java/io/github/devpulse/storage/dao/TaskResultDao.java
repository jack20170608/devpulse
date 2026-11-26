package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.TaskResult;
import io.github.devpulse.storage.TaskResultKey;
import io.github.devpulse.storage.jdbi.Bindings;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * DAO for the {@code task_result} table, the cross-run reuse index.
 *
 * <p>The key includes the project: the same task name and fingerprint mean different things in
 * different projects, so reuse must never cross that boundary.
 */
public final class TaskResultDao extends BaseDaoJdbiImpl<TaskResult, TaskResultKey> {

    private static final String MERGE =
            """
            MERGE INTO task_result (project_id, name, fingerprint, run_id, finished_at)
            KEY (project_id, name, fingerprint)
            VALUES (:projectId, :name, :fingerprint, :runId, :finishedAt)
            """;

    private static final String SELECT_KEY =
            """
            SELECT * FROM task_result
            WHERE project_id = :projectId AND name = :name AND fingerprint = :fingerprint
            """;

    public TaskResultDao(Jdbi jdbi) {
        super(
                description(),
                jdbi,
                result ->
                        new TaskResultKey(result.projectId(), result.name(), result.fingerprint()));
    }

    private static TableDescription description() {
        return TableDescription.builder()
                .withName("task_result")
                .withEntityClass(TaskResult.class)
                .withIdFields(List.of("projectId", "name", "fingerprint"))
                .build();
    }

    /** Publish a finished result, replacing the entry for the same key. */
    public void merge(TaskResult result) {
        jdbi.useHandle(handle -> {
            var update = handle.createUpdate(MERGE);
            Bindings.text(update, "projectId", result.projectId());
            Bindings.text(update, "name", result.name());
            Bindings.text(update, "fingerprint", result.fingerprint());
            Bindings.text(update, "runId", result.runId());
            Bindings.instant(update, "finishedAt", result.finishedAt());
            update.execute();
        });
    }

    /** The recorded success for this key, if any. */
    public Optional<TaskResult> successful(String projectId, String name, String fingerprint) {
        return jdbi.withHandle(handle ->
                handle.createQuery(SELECT_KEY)
                        .bind("projectId", projectId)
                        .bind("name", name)
                        .bind("fingerprint", fingerprint)
                        .mapTo(TaskResult.class)
                        .findOne());
    }

    @Override
    protected void registerRowMappers(Jdbi jdbi) {
        jdbi.registerRowMapper(TaskResult.class, (result, context) -> map(result));
    }

    private static TaskResult map(ResultSet result) throws SQLException {
        return new TaskResult(
                result.getString("project_id"),
                result.getString("name"),
                result.getString("fingerprint"),
                result.getString("run_id"),
                Bindings.instant(result, "finished_at"));
    }
}
