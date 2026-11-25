package io.github.devpulse.storage;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

/**
 * {@link RunQueries} over JDBI with explicit SQL.
 *
 * <p>The filter is appended by hand instead of going through a generic criteria builder: there are
 * two optional columns, the SQL stays readable, and the aggregate has to be visible anyway because
 * it is what the list actually shows.
 */
final class JdbiRunQueries implements RunQueries {

    /** Task states that count as a failure in the summary. */
    private static final String FAILED_TASK_STATES = "'FAILED', 'TIMED_OUT'";

    private final Jdbi jdbi;

    JdbiRunQueries(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    }

    @Override
    public List<RunSummary> runs(String project, String state, int limit, int offset)
            throws IOException {
        var filter = new Filter(project, state);
        var sql =
                """
                SELECT r.id, r.target, r.state, r.started_at, r.finished_at,
                       COUNT(t.name) AS task_count,
                       COALESCE(SUM(CASE WHEN t.state = 'SUCCESS' THEN 1 ELSE 0 END), 0)
                           AS succeeded,
                       COALESCE(SUM(CASE WHEN t.state IN (%s) THEN 1 ELSE 0 END), 0) AS failed
                  FROM run r
                  LEFT JOIN task t ON t.run_id = r.id
                %s
                 GROUP BY r.id, r.target, r.state, r.started_at, r.finished_at
                 ORDER BY r.started_at DESC, r.id DESC
                 LIMIT :limit OFFSET :offset
                """
                        .formatted(FAILED_TASK_STATES, filter.whereClause());
        try {
            return jdbi.withHandle(handle -> {
                var query = handle.createQuery(sql);
                filter.bind(query::bind);
                query.bind("limit", limit);
                query.bind("offset", offset);
                return query.map((result, context) -> summary(result)).list();
            });
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    @Override
    public long count(String project, String state) throws IOException {
        var filter = new Filter(project, state);
        try {
            return jdbi.withHandle(handle -> {
                var query =
                        handle.createQuery("SELECT COUNT(*) FROM run r " + filter.whereClause());
                filter.bind(query::bind);
                return query.mapTo(Long.class).one();
            });
        } catch (JdbiException exception) {
            throw wrap(exception);
        }
    }

    private static RunSummary summary(ResultSet result) throws SQLException {
        return new RunSummary(
                result.getString("id"),
                result.getString("target"),
                result.getString("state"),
                instant(result, "started_at"),
                instant(result, "finished_at"),
                result.getInt("task_count"),
                result.getInt("succeeded"),
                result.getInt("failed"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static IOException wrap(JdbiException exception) {
        return new IOException("Cannot read run summaries: " + exception.getMessage(), exception);
    }

    /** The optional predicates, kept together so the query and the count cannot drift apart. */
    private record Filter(String project, String state) {

        String whereClause() {
            var predicates = new ArrayList<String>(2);
            if (project != null) {
                predicates.add("r.project = :project");
            }
            if (state != null) {
                predicates.add("r.state = :state");
            }
            return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        }

        void bind(java.util.function.BiConsumer<String, Object> binder) {
            if (project != null) {
                binder.accept("project", project);
            }
            if (state != null) {
                binder.accept("state", state);
            }
        }
    }
}
