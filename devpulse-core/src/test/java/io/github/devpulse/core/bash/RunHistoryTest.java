package io.github.devpulse.core.bash;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpulse.storage.JdbiRunStore;
import io.github.devpulse.storage.RunRecord;
import io.github.devpulse.storage.TaskRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the history reads: callers that show only the latest run must not pay for observing the
 * whole history, and a full listing must still return every run with its tasks in one pass.
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
        record(project, "older", BASE, List.of());
        record(project, "newer", BASE.plusSeconds(60), List.of());

        var latest = new RunHistory(project).latest();

        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().snapshot().id()).isEqualTo("newer");
    }

    @Test
    void listReturnsEveryRunNewestFirstWithItsTasks(@TempDir Path project) throws IOException {
        record(project, "older", BASE, List.of(task("older", "build", List.of())));
        record(
                project,
                "newer",
                BASE.plusSeconds(60),
                List.of(task("newer", "test", List.of("build"))));

        var list = new RunHistory(project).list();

        assertThat(list).extracting(item -> item.snapshot().id()).containsExactly("newer", "older");
        assertThat(list.getFirst().snapshot().tasks())
                .singleElement()
                .satisfies(state -> assertThat(state.needs()).containsExactly("build"));
    }

    private static TaskRecord task(String runId, String name, List<String> needs) {
        return new TaskRecord(
                runId,
                name,
                name + "-id",
                "fp-" + name,
                "SUCCESS",
                0,
                null,
                BASE,
                BASE.plusSeconds(1),
                "logs/" + name + ".log",
                null,
                null,
                null,
                null,
                0,
                needs);
    }

    private static void record(Path project, String id, Instant startedAt, List<TaskRecord> tasks)
            throws IOException {
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
                    tasks);
        }
    }
}
