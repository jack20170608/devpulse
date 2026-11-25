package io.github.devpulse.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

/**
 * Applies the ordered schema migrations kept under {@code db/}.
 *
 * <p>The SQL lives in resources, not in this class: a migration is a file, and its number is the
 * schema version it produces. Applied versions are recorded in {@code schema_version}. Never edit a
 * shipped migration; add the next numbered file and bump {@link #VERSION}, or stores created by an
 * earlier build silently diverge.
 */
final class Schema {
    /** The version this build expects after migration. */
    static final int VERSION = 2;

    /** Applied before the numbered steps, because they record themselves in this table. */
    private static final String BOOTSTRAP = "db/bootstrap/schema-version.sql";

    /** One resource per version: entry {@code i} produces version {@code i + 1}. */
    static final List<String> MIGRATIONS =
            List.of("db/migration/V1__run-and-task.sql", "db/migration/V2__daemon-instance.sql");

    private Schema() {}

    /**
     * Apply every migration the store is missing, in one transaction.
     *
     * <p>A store already at {@link #VERSION} is left untouched: migrating it again would commit an
     * empty DDL transaction on every open, which costs more than the read the open exists for.
     */
    static void migrate(Jdbi jdbi) throws IOException {
        try {
            if (jdbi.withHandle(Schema::isCurrent)) {
                return;
            }
        } catch (JdbiException ignored) {
            // No readable schema_version yet: the full migration below creates it.
        }
        try {
            jdbi.useTransaction(handle -> {
                execute(handle, BOOTSTRAP);
                var current = currentVersion(handle);
                if (current > VERSION) {
                    throw new IllegalStateException(
                            "Store schema version "
                                    + current
                                    + " is newer than this build supports ("
                                    + VERSION
                                    + ").");
                }
                for (var version = current + 1; version <= VERSION; version++) {
                    execute(handle, MIGRATIONS.get(version - 1));
                    handle.createUpdate(
                                    "INSERT INTO schema_version (version, applied_at) "
                                            + "VALUES (:version, :appliedAt)")
                            .bind("version", version)
                            .bind("appliedAt", OffsetDateTime.now(ZoneOffset.UTC))
                            .execute();
                }
            });
        } catch (JdbiException | IllegalStateException exception) {
            throw new IOException(
                    "Cannot migrate the run store: " + exception.getMessage(), exception);
        }
    }

    private static void execute(Handle handle, String resource) {
        for (var statement : statements(read(resource))) {
            handle.execute(statement);
        }
    }

    /**
     * Read one SQL resource from the classpath.
     *
     * <p>A missing resource is a packaging error rather than an I/O failure: the migrations ship in
     * the same artifact as this class.
     */
    private static String read(String resource) {
        try (InputStream stream = Schema.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing schema resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read schema resource: " + resource, exception);
        }
    }

    /**
     * Split a migration file into the statements JDBI executes one at a time.
     *
     * <p>Semicolons separate statements; line comments are dropped; a semicolon inside a quoted
     * literal does not split, so a default value or a command containing one stays intact.
     */
    static List<String> statements(String sql) {
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < sql.length(); index++) {
            var character = sql.charAt(index);
            if (!quoted
                    && character == '-'
                    && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
            }
            if (character == ';' && !quoted) {
                add(statements, current);
                continue;
            }
            current.append(character);
        }
        add(statements, current);
        return List.copyOf(statements);
    }

    private static void add(List<String> statements, StringBuilder current) {
        var statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /**
     * Whether the store already carries the version this build expects.
     *
     * <p>H2 stores unquoted identifiers uppercase, so the table lookup asks for {@code
     * SCHEMA_VERSION}. A version above {@link #VERSION} is not current: the full migration reports
     * it instead of silently ignoring a newer store.
     */
    private static boolean isCurrent(Handle handle) {
        var present =
                handle.createQuery(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                        + "WHERE TABLE_NAME = 'SCHEMA_VERSION'")
                        .mapTo(Integer.class)
                        .one();
        return present > 0 && currentVersion(handle) == VERSION;
    }

    static int currentVersion(Jdbi jdbi) throws IOException {
        try {
            return jdbi.withHandle(Schema::currentVersion);
        } catch (JdbiException exception) {
            throw new IOException(
                    "Cannot read the run store schema version: " + exception.getMessage(),
                    exception);
        }
    }

    private static int currentVersion(Handle handle) {
        return handle.createQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")
                .mapTo(Integer.class)
                .one();
    }
}
