package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code BaseDao} can be inherited with keys other than a generated {@code Long}: a
 * caller-supplied string key and a composite key both go through the same inherited CRUD.
 */
class DaoInheritanceTest {
    private static final Instant BASE = Instant.parse("2026-11-20T00:00:00Z");

    private RunDao runs;
    private TaskDao tasks;

    @BeforeEach
    void open() throws IOException {
        var jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Schema.migrate(jdbi);
        runs = new RunDao(jdbi);
        tasks = new TaskDao(jdbi);
    }

    private static RunRecord run(String id) {
        return new RunRecord(
                id, "proj", "dev", 4, "RUNNING", 42L, BASE, BASE, null, "/bin/bash", "1.0", null);
    }

    private static TaskRecord task(String runId, String name) {
        return new TaskRecord(
                runId,
                name,
                name + "-id",
                "fp-" + name,
                "PENDING",
                null,
                null,
                null,
                null,
                "logs/" + name + ".log",
                null,
                null,
                null,
                null,
                0,
                List.of());
    }

    @Test
    void createsAndFindsARunByItsStringKey() {
        assertThat(runs.create(run("r1"))).isEqualTo(new RunKey("r1"));

        var found = runs.findOne(new RunKey("r1")).orElseThrow();

        assertThat(found.project()).isEqualTo("proj");
        assertThat(found.startedAt()).isEqualTo(BASE);
        assertThat(found.finishedAt()).isNull();
        assertThat(runs.exists(new RunKey("r1"))).isTrue();
        assertThat(runs.exists(new RunKey("missing"))).isFalse();
        assertThat(runs.countAll()).isEqualTo(1);
        assertThat(runs.findAll()).hasSize(1);
    }

    @Test
    void updatesAndDeletesARunByItsKey() {
        runs.create(run("r1"));
        var updated =
                new RunRecord(
                        "r1",
                        "proj",
                        "dev",
                        8,
                        "SUCCESS",
                        42L,
                        BASE,
                        BASE,
                        BASE.plusSeconds(5),
                        "/bin/bash",
                        "1.0",
                        null);

        assertThat(runs.update(new RunKey("r1"), updated)).isEqualTo(1);

        var found = runs.findOne(new RunKey("r1")).orElseThrow();
        assertThat(found.state()).isEqualTo("SUCCESS");
        assertThat(found.parallelism()).isEqualTo(8);
        assertThat(found.finishedAt()).isEqualTo(BASE.plusSeconds(5));

        assertThat(runs.delete(new RunKey("r1"))).isEqualTo(1);
        assertThat(runs.findOne(new RunKey("r1"))).isEmpty();
    }

    @Test
    void createsAndFindsATaskByItsCompositeKey() {
        runs.create(run("r1"));

        assertThat(tasks.create(task("r1", "build"))).isEqualTo(new TaskKey("r1", "build"));

        var found = tasks.findOne(new TaskKey("r1", "build")).orElseThrow();
        assertThat(found.state()).isEqualTo("PENDING");
        assertThat(found.logPath()).isEqualTo("logs/build.log");
        assertThat(found.needs()).isEmpty();
        assertThat(tasks.exists(new TaskKey("r1", "missing"))).isFalse();
        assertThat(tasks.findOne(new TaskKey("other", "build"))).isEmpty();
    }

    @Test
    void updatesATaskByItsCompositeKey() {
        runs.create(run("r1"));
        tasks.create(task("r1", "build"));
        var updated =
                new TaskRecord(
                        "r1",
                        "build",
                        "build-id",
                        "fp-build",
                        "SUCCESS",
                        0,
                        null,
                        BASE,
                        BASE.plusSeconds(2),
                        "logs/build.log",
                        null,
                        null,
                        null,
                        null,
                        0,
                        List.of());

        assertThat(tasks.update(new TaskKey("r1", "build"), updated)).isEqualTo(1);

        var found = tasks.findOne(new TaskKey("r1", "build")).orElseThrow();
        assertThat(found.state()).isEqualTo("SUCCESS");
        assertThat(found.exitCode()).isZero();
        assertThat(found.finishedAt()).isEqualTo(BASE.plusSeconds(2));
    }

    @Test
    void deletesTasksByTheirCompositeKeys() {
        runs.create(run("r1"));
        tasks.create(task("r1", "build"));
        tasks.create(task("r1", "test"));

        assertThat(tasks.delete(List.of(new TaskKey("r1", "build"), new TaskKey("r1", "test"))))
                .isEqualTo(2);
        assertThat(tasks.findAll()).isEmpty();
    }
}
