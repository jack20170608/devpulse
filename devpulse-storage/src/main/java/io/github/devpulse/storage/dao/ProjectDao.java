package io.github.devpulse.storage.dao;

import io.github.devpulse.storage.ProjectRecord;
import io.github.devpulse.storage.jdbi.Bindings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * DAO for the {@code project} table.
 *
 * <p>Not a {@code BaseDao}: a project has no generated key and no generic CRUD need, so the two
 * statements it requires are written out.
 */
public final class ProjectDao {

    private static final String UPSERT =
            """
            MERGE INTO project (id, path, name, created_at, last_seen_at)
            KEY (id)
            VALUES (:id, :path, :name, :createdAt, :lastSeenAt)
            """;

    private final Jdbi jdbi;

    public ProjectDao(Jdbi jdbi) {
        this.jdbi = jdbi;
        jdbi.registerRowMapper(ProjectRecord.class, (result, context) -> map(result));
    }

    /** Insert the project, or refresh the fields a later visit can change. */
    public void upsert(ProjectRecord project) {
        jdbi.useHandle(handle -> upsert(handle, project));
    }

    /** The same upsert inside a caller's transaction. */
    public void upsert(org.jdbi.v3.core.Handle handle, ProjectRecord project) {
        var update = handle.createUpdate(UPSERT);
        Bindings.text(update, "id", project.id());
        Bindings.text(update, "path", project.path());
        Bindings.text(update, "name", project.name());
        Bindings.instant(update, "createdAt", project.createdAt());
        Bindings.instant(update, "lastSeenAt", project.lastSeenAt());
        update.execute();
    }

    public Optional<ProjectRecord> find(String id) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM project WHERE id = :id")
                        .bind("id", id)
                        .mapTo(ProjectRecord.class)
                        .findOne());
    }

    public Optional<ProjectRecord> findByPath(String path) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM project WHERE path = :path")
                        .bind("path", path)
                        .mapTo(ProjectRecord.class)
                        .findOne());
    }

    private static ProjectRecord map(ResultSet result) throws SQLException {
        return new ProjectRecord(
                result.getString("id"),
                result.getString("path"),
                result.getString("name"),
                Bindings.instant(result, "created_at"),
                Bindings.instant(result, "last_seen_at"));
    }
}
