package io.github.devpulse.storage;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

/**
 * Schema definition and ordered migrations, executed through JDBI.
 *
 * <p>Every schema change is a new numbered step in {@link #VERSION_1}; applied versions are
 * recorded in {@code schema_version}. Editing an existing step instead of adding one would silently
 * corrupt stores created by an earlier build.
 */
final class Schema {
    /** The version this build expects after migration. */
    static final int VERSION = 1;

    private static final List<String> VERSION_1 =
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS run (
                        id VARCHAR(64) NOT NULL PRIMARY KEY,
                        project VARCHAR(1024) NOT NULL,
                        target VARCHAR(256) NOT NULL,
                        parallelism INT NOT NULL,
                        state VARCHAR(32) NOT NULL,
                        executor_pid BIGINT NOT NULL,
                        executor_started_at TIMESTAMP WITH TIME ZONE,
                        started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        finished_at TIMESTAMP WITH TIME ZONE,
                        bash VARCHAR(1024),
                        tool_version VARCHAR(128),
                        parent_run_id VARCHAR(64)
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS run_project_started
                        ON run (project, started_at)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS task (
                        run_id VARCHAR(64) NOT NULL,
                        name VARCHAR(256) NOT NULL,
                        task_id VARCHAR(64) NOT NULL,
                        fingerprint VARCHAR(128),
                        state VARCHAR(32) NOT NULL,
                        exit_code INT,
                        error VARCHAR(4096),
                        started_at TIMESTAMP WITH TIME ZONE,
                        finished_at TIMESTAMP WITH TIME ZONE,
                        log_path VARCHAR(1024),
                        reused_from VARCHAR(64),
                        daemon_health VARCHAR(32),
                        daemon_error VARCHAR(4096),
                        daemon_checked_at TIMESTAMP WITH TIME ZONE,
                        sort_order INT NOT NULL,
                        PRIMARY KEY (run_id, name),
                        CONSTRAINT task_run FOREIGN KEY (run_id) REFERENCES run (id)
                            ON DELETE CASCADE,
                        CONSTRAINT task_id_unique UNIQUE (run_id, task_id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS task_need (
                        run_id VARCHAR(64) NOT NULL,
                        task_name VARCHAR(256) NOT NULL,
                        need_name VARCHAR(256) NOT NULL,
                        sort_order INT NOT NULL,
                        PRIMARY KEY (run_id, task_name, need_name),
                        CONSTRAINT need_task FOREIGN KEY (run_id, task_name)
                            REFERENCES task (run_id, name) ON DELETE CASCADE
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS task_result (
                        name VARCHAR(256) NOT NULL,
                        fingerprint VARCHAR(128) NOT NULL,
                        run_id VARCHAR(64),
                        finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        PRIMARY KEY (name, fingerprint),
                        CONSTRAINT result_run FOREIGN KEY (run_id) REFERENCES run (id)
                            ON DELETE SET NULL
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS task_result_name ON task_result (name)
                    """);

    private static final List<List<String>> MIGRATIONS = List.of(VERSION_1);

    private Schema() {}

    /** Apply every migration the store is missing, in one transaction. */
    static void migrate(Jdbi jdbi) throws IOException {
        try {
            jdbi.useTransaction(handle -> {
                handle.execute(
                        """
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version INT NOT NULL PRIMARY KEY,
                            applied_at TIMESTAMP WITH TIME ZONE NOT NULL
                        )
                        """);
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
                    for (var sql : MIGRATIONS.get(version - 1)) {
                        handle.execute(sql);
                    }
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
