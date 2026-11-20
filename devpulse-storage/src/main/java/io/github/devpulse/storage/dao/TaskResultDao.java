package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.TaskResult;
import io.github.devpulse.storage.TaskResultKey;
import io.github.devpulse.storage.jdbi.Bindings;
import io.github.devpulse.storage.jdbi.TableDescription;
import io.github.devpulse.storage.jdbi.dao.BaseDaoJdbiImpl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * DAO for the {@code task_result} table, the cross-run reuse index.
 *
 * <p>Publishing a result is an upsert because one key keeps only its latest success, which the
 * generic CRUD base cannot express; the SQL lives here rather than in the caller.
 */
public final class TaskResultDao extends BaseDaoJdbiImpl<TaskResult, TaskResultKey> {
    private static final String MERGE =
            """
            MERGE INTO task_result (name, fingerprint, run_id, finished_at)
            KEY (name, fingerprint)
            VALUES (:name, :fingerprint, :runId, :finishedAt)
            """;

    private static final String SELECT_KEY =
            """
            SELECT * FROM task_result WHERE name = :name AND fingerprint = :fingerprint
            """;

    public TaskResultDao(Jdbi jdbi) {
        super(
                description(),
                jdbi,
                result -> new TaskResultKey(result.name(), result.fingerprint()));
    }

    private static TableDescription description() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("name", "name");
        columns.put("fingerprint", "fingerprint");
        columns.put("runId", "run_id");
        columns.put("finishedAt", "finished_at");
        return TableDescription.builder()
                .withName("task_result")
                .withEntityClass(TaskResult.class)
                .withIdFields(List.of("name", "fingerprint"))
                .withFieldColumnMap(columns)
                .build();
    }

    /** Publish a finished result, replacing the entry for the same key. */
    public void merge(TaskResult result) {
        jdbi.useHandle(handle -> {
            var update = handle.createUpdate(MERGE);
            Bindings.text(update, "name", result.name());
            Bindings.text(update, "fingerprint", result.fingerprint());
            Bindings.text(update, "runId", result.runId());
            Bindings.instant(update, "finishedAt", result.finishedAt());
            update.execute();
        });
    }

    /** The recorded success for this key, if any. */
    public Optional<TaskResult> successful(String name, String fingerprint) {
        return jdbi.withHandle(handle ->
                handle.createQuery(SELECT_KEY)
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
                result.getString("name"),
                result.getString("fingerprint"),
                result.getString("run_id"),
                Bindings.instant(result, "finished_at"));
    }
}
