package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guards the daemon instance table that replaced the per-instance metadata files. */
class DaemonRecordTest {
    private static final Instant BASE = Instant.parse("2026-11-20T00:00:00Z");

    private RunStore store;

    @BeforeEach
    void open() throws IOException {
        store = JdbiRunStore.open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store.migrate();
    }

    @AfterEach
    void close() throws IOException {
        store.close();
    }

    private void run(String runId) throws IOException {
        store.begin(
                new RunRecord(
                        runId,
                        "proj",
                        "dev",
                        4,
                        "RUNNING",
                        1234L,
                        BASE,
                        BASE,
                        null,
                        "/bin/bash",
                        "1.0",
                        null),
                java.util.List.of());
    }

    private static DaemonRecord daemon(
            String runId, String taskId, String instanceId, int attempt) {
        return new DaemonRecord(
                runId,
                taskId,
                instanceId,
                attempt,
                "server",
                "/proj",
                "/bin/bash",
                4242L,
                BASE.plusSeconds(1),
                "curl --fail http://127.0.0.1:3000/health",
                null,
                5_000_000_000L,
                500_000_000L,
                30_000_000_000L,
                2_000_000_000L,
                null,
                null);
    }

    @Test
    void recordsAndReadsADaemonInstance() throws IOException {
        run("r1");
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1));

        var found = store.daemons("r1", "task-1");
        assertThat(found).hasSize(1);
        var record = found.getFirst();
        assertThat(record.instanceId()).isEqualTo("instance-1");
        assertThat(record.pid()).isEqualTo(4242L);
        assertThat(record.processStartedAt()).isEqualTo(BASE.plusSeconds(1));
        assertThat(record.readyCommand()).startsWith("curl");
        assertThat(record.stopTimeoutNanos()).isEqualTo(5_000_000_000L);
        assertThat(record.readyAt()).isNull();
    }

    @Test
    void replacesAnInstanceWhenReadinessIsObserved() throws IOException {
        run("r1");
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1));

        var ready = daemon("r1", "task-1", "instance-1", 1);
        var withReadyAt =
                new DaemonRecord(
                        ready.runId(),
                        ready.taskId(),
                        ready.instanceId(),
                        ready.attempt(),
                        ready.name(),
                        ready.project(),
                        ready.bash(),
                        ready.pid(),
                        ready.processStartedAt(),
                        ready.readyCommand(),
                        ready.stopCommand(),
                        ready.stopTimeoutNanos(),
                        ready.intervalNanos(),
                        ready.timeoutNanos(),
                        ready.checkTimeoutNanos(),
                        BASE.plusSeconds(9),
                        null);
        store.recordDaemon(withReadyAt);

        var found = store.daemons("r1", "task-1");
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().readyAt()).isEqualTo(BASE.plusSeconds(9));
    }

    @Test
    void ordersInstancesByAttempt() throws IOException {
        run("r1");
        store.recordDaemon(daemon("r1", "task-1", "instance-2", 2));
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1));

        assertThat(store.daemons("r1", "task-1"))
                .extracting(DaemonRecord::attempt)
                .containsExactly(1, 2);
    }

    @Test
    void scopesInstancesToTheirTask() throws IOException {
        run("r1");
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1));
        store.recordDaemon(daemon("r1", "task-2", "instance-9", 1));

        assertThat(store.daemons("r1", "task-1"))
                .extracting(DaemonRecord::instanceId)
                .containsExactly("instance-1");
    }

    @Test
    void rejectsADaemonForAnUnknownRun() throws IOException {
        assertThatThrownBy(() -> store.recordDaemon(daemon("missing", "task-1", "instance-1", 1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsTwoInstancesWithTheSameIdInOneRun() throws IOException {
        run("r1");
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1));

        assertThatThrownBy(() -> store.recordDaemon(daemon("r1", "task-2", "instance-1", 1)))
                .isInstanceOf(IOException.class);
    }
}
