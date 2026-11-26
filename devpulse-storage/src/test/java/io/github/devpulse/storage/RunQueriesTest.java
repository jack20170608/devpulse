package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards the read model: the tallies, the newest-first order, the filters and the paging. */
class RunQueriesTest {

    private static final Instant BASE = Instant.parse("2026-11-25T00:00:00Z");
    private static final ProjectRecord PROJECT =
            new ProjectRecord("proj", "/proj", "proj", BASE, BASE);

    @Test
    void listsRunsNewestFirstWithTaskTallies() throws IOException {
        try (var store = JdbiRunStore.open(memoryUrl())) {
            store.migrate();
            seed(store);

            var runs = store.queries().runs(null, null, 10, 0);

            assertThat(runs).extracting(RunSummary::id).containsExactly("r3", "r2", "r1");
            assertThat(runs.get(1))
                    .satisfies(run -> {
                        assertThat(run.state()).isEqualTo("FAILED");
                        assertThat(run.taskCount()).isEqualTo(2);
                        assertThat(run.succeeded()).isEqualTo(1);
                        assertThat(run.failed()).isEqualTo(1);
                    });
            assertThat(runs.get(2).succeeded()).isEqualTo(2);
            assertThat(runs.getFirst().taskCount()).isZero();
        }
    }

    @Test
    void filtersByStateAndCountsTheSameSet() throws IOException {
        try (var store = JdbiRunStore.open(memoryUrl())) {
            store.migrate();
            seed(store);

            assertThat(store.queries().runs(null, "FAILED", 10, 0))
                    .extracting(RunSummary::id)
                    .containsExactly("r2");
            assertThat(store.queries().count(null, "FAILED")).isEqualTo(1);
            assertThat(store.queries().count(null, null)).isEqualTo(3);
        }
    }

    @Test
    void pagesWithoutChangingTheOrder() throws IOException {
        try (var store = JdbiRunStore.open(memoryUrl())) {
            store.migrate();
            seed(store);

            assertThat(store.queries().runs(null, null, 2, 0))
                    .extracting(RunSummary::id)
                    .containsExactly("r3", "r2");
            assertThat(store.queries().runs(null, null, 2, 2))
                    .extracting(RunSummary::id)
                    .containsExactly("r1");
        }
    }

    private static String memoryUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static void seed(RunStore store) throws IOException {
        store.begin(
                PROJECT,
                run("r1", BASE, "SUCCESS"),
                List.of(task("r1", "build", "SUCCESS"), task("r1", "test", "SUCCESS")));
        store.begin(
                PROJECT,
                run("r2", BASE.plusSeconds(60), "FAILED"),
                List.of(task("r2", "build", "SUCCESS"), task("r2", "deploy", "FAILED")));
        store.begin(PROJECT, run("r3", BASE.plusSeconds(120), "SUCCESS"), List.of());
    }

    private static RunRecord run(String id, Instant startedAt, String state) {
        return new RunRecord(
                id,
                PROJECT.id(),
                "dev",
                1,
                state,
                null,
                startedAt,
                startedAt,
                startedAt.plusSeconds(1),
                1L,
                startedAt,
                null,
                "/bin/bash",
                "0.1.5",
                null);
    }

    private static TaskRecord task(String runId, String name, String state) {
        return new TaskRecord(
                runId,
                name,
                name + "-id",
                0,
                "fp-" + name,
                state,
                0,
                null,
                null,
                BASE,
                BASE.plusSeconds(1),
                "logs/" + name + ".log",
                null,
                List.of());
    }
}
