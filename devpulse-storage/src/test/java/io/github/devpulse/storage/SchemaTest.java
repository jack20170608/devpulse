package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Guards the migration files: they ship as resources, so a missing file, a forgotten version bump,
 * or a statement splitter that misreads a quoted semicolon would only surface at runtime.
 */
class SchemaTest {

    @Test
    void oneMigrationFilePerVersion() {
        assertThat(Schema.VERSION)
                .as("every version needs exactly one file under db/migration")
                .isEqualTo(Schema.MIGRATIONS.size());
    }

    @Test
    void everyMigrationResourceIsOnTheClasspath() {
        assertThat(Schema.MIGRATIONS)
                .allSatisfy(resource ->
                        assertThat(Schema.class.getClassLoader().getResource(resource))
                                .as(resource)
                                .isNotNull());
    }

    @Test
    void splitsStatementsOnSemicolonsAndDropsComments() {
        var sql =
                """
                -- create the first table
                CREATE TABLE a (id INT);
                INSERT INTO a VALUES ('x;y');
                CREATE TABLE b (id INT)
                """;

        var statements = Schema.statements(sql);

        assertThat(statements).hasSize(3);
        assertThat(statements).noneMatch(statement -> statement.startsWith("--"));
        assertThat(statements.get(1)).contains("'x;y'");
        assertThat(statements.get(2)).endsWith("CREATE TABLE b (id INT)");
    }

    @Test
    void migrationCreatesEveryTableAndRecordsTheVersion() throws IOException {
        var jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");

        Schema.migrate(jdbi);

        assertThat(tables(jdbi))
                .contains(
                        "RUN",
                        "TASK",
                        "TASK_NEED",
                        "TASK_RESULT",
                        "DAEMON_INSTANCE",
                        "SCHEMA_VERSION");
        assertThat(Schema.currentVersion(jdbi)).isEqualTo(Schema.VERSION);
    }

    @Test
    void migratingAnUpToDateStoreChangesNothing() throws IOException {
        var jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");

        Schema.migrate(jdbi);
        Schema.migrate(jdbi);

        assertThat(Schema.currentVersion(jdbi)).isEqualTo(Schema.VERSION);
        assertThat(versions(jdbi)).hasSize(Schema.VERSION);
    }

    private static List<String> tables(Jdbi jdbi) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES")
                        .mapTo(String.class)
                        .list());
    }

    private static List<Integer> versions(Jdbi jdbi) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT version FROM schema_version ORDER BY version")
                        .mapTo(Integer.class)
                        .list());
    }
}
