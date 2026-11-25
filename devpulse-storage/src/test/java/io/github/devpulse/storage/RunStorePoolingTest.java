package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Guards the pooled factory: the server path borrows connections from a pool and has to release
 * them, which the direct (CLI) path never does.
 */
class RunStorePoolingTest {

    @Test
    void pooledStoreReadsAndWritesLikeTheDirectOne() throws IOException {
        try (var store = JdbiRunStore.pooled(memoryUrl())) {
            store.migrate();
            store.begin(run("r1"), List.of());

            assertThat(store.schemaVersion()).isEqualTo(Schema.VERSION);
            assertThat(store.run("r1")).isPresent();
            assertThat(store.runs(null, 10)).extracting(RunRecord::id).containsExactly("r1");
        }
    }

    @Test
    void pooledStoreServesConcurrentReaders() throws Exception {
        try (var store = JdbiRunStore.pooled(memoryUrl());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            store.migrate();
            store.begin(run("r1"), List.of());

            List<Callable<Integer>> reads =
                    IntStream.range(0, 16)
                            .<Callable<Integer>>mapToObj(index -> store::schemaVersion)
                            .toList();

            for (var result : executor.invokeAll(reads)) {
                assertThat(result.get()).isEqualTo(Schema.VERSION);
            }
        }
    }

    private static String memoryUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static RunRecord run(String id) {
        var started = Instant.parse("2026-11-25T00:00:00Z");
        return new RunRecord(
                id,
                "/tmp/project",
                "dev",
                1,
                "SUCCESS",
                1L,
                started,
                started,
                started.plusSeconds(1),
                "/bin/bash",
                "0.1.5",
                null);
    }
}
