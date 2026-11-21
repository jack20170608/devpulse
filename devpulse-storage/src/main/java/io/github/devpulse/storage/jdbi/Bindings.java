package io.github.devpulse.storage.jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jdbi.v3.core.statement.SqlStatement;

/**
 * Null-safe parameter binding shared by the store and the table DAOs.
 *
 * <p>JDBI cannot infer a SQL type for a {@code null} value, while most of these columns are
 * nullable. Binding every nullable column through these helpers keeps the SQL type explicit and
 * keeps instants in UTC regardless of the session zone.
 */
public final class Bindings {
    private Bindings() {}

    public static void text(SqlStatement<?> statement, String name, String value) {
        if (value == null) {
            statement.bindNull(name, Types.VARCHAR);
        } else {
            statement.bind(name, value);
        }
    }

    public static void number(SqlStatement<?> statement, String name, Integer value) {
        if (value == null) {
            statement.bindNull(name, Types.INTEGER);
        } else {
            statement.bind(name, value);
        }
    }

    public static void instant(SqlStatement<?> statement, String name, Instant value) {
        if (value == null) {
            statement.bindNull(name, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.bind(name, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    /** Read a nullable instant column as a UTC instant. */
    public static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Read a nullable integer column. */
    public static Integer integer(ResultSet result, String column) throws SQLException {
        var value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
