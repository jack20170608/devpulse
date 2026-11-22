package io.github.devpulse.core.bash;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpulse.storage.JdbiRunStore;
import io.github.devpulse.storage.RunRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the newest-run read: callers that show only the latest run must not pay for observing the
 * whole history, which is what made {@code status} slower with every recorded run.
 */
class RunHistoryTest {
    private static final Instant BASE = Instant.parse("2026-11-20T00:00:00Z");

    @Test
    void latestIsEmptyWhenNoRunWasRecorded(@TempDir Path project) throws IOException {
        Files.createDirectories(project.resolve(".devpulse"));

        assertThat(new RunHistory(project).latest()).isEmpty();
    }

    @Test
    void latestReturnsTheNewestRun(@TempDir Path project) throws IOException {
        record(project, "older", BASE);
        record(project, "newer", BASE.plusSeconds(60));

        var latest = new RunHistory(project).latest();

        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().snapshot().id()).isEqualTo("newer");
    }

    private static void record(Path project, String id, Instant startedAt) throws IOException {
        Files.createDirectories(project.resolve(".devpulse"));
        try (var store = JdbiRunStore.open(project.resolve(".devpulse").resolve("state"))) {
            store.migrate();
            store.begin(
                    new RunRecord(
                            id,
                            project.toString(),
                            "dev",
                            1,
                            "SUCCESS",
                            1234L,
                            startedAt,
                            startedAt,
                            startedAt.plusSeconds(1),
                            "/bin/bash",
                            "0.1.5",
                            null),
                    List.of());
        }
    }
}
