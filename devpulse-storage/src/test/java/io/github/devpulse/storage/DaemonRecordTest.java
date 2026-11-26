package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guards the daemon instance table that replaced the per-instance metadata files. */
class DaemonRecordTest {
    private static final Instant BASE = Instant.parse("2026-11-20T00:00:00Z");
    private static final ProjectRecord PROJECT =
            new ProjectRecord("p1", "/proj", "proj", BASE, BASE);

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

    /** A run that owns the two nodes the daemon tests refer to. */
    private void run(String runId) throws IOException {
        store.begin(
                PROJECT,
                new RunRecord(
                        runId,
                        PROJECT.id(),
                        "dev",
                        4,
                        "RUNNING",
                        null,
                        BASE,
                        BASE,
                        null,
                        1234L,
                        BASE,
                        null,
                        "/bin/bash",
                        "1.0",
                        null),
                List.of(task(runId, "server", "task-1"), task(runId, "other", "task-2")));
    }

    private static TaskRecord task(String runId, String name, String taskId) {
        return new TaskRecord(
                runId,
                name,
                taskId,
                0,
                "fp-" + name,
                "RUNNING",
                null,
                null,
                null,
                BASE,
                null,
                "logs/" + taskId + ".log",
                null,
                List.of());
    }

    private static DaemonRecord daemon(
            String runId, String taskId, String instanceId, int attempt) {
        return daemon(runId, taskId, instanceId, attempt, null);
    }

    private static DaemonRecord daemon(
            String runId, String taskId, String instanceId, int attempt, Instant readyAt) {
        return new DaemonRecord(
                runId,
                taskId,
                instanceId,
                attempt,
                "server",
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
                null,
                null,
                readyAt,
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
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1, BASE.plusSeconds(9)));

        var found = store.daemons("r1", "task-1");
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().readyAt()).isEqualTo(BASE.plusSeconds(9));
    }

    @Test
    void recordsHealthOnTheLatestInstance() throws IOException {
        run("r1");
        store.recordDaemon(daemon("r1", "task-1", "instance-1", 1));

        store.recordDaemonHealth("r1", "task-1", "READY", null, BASE.plusSeconds(5));

        assertThat(store.daemons("r1", "task-1").getFirst().health()).isEqualTo("READY");
        assertThat(store.daemons("r1").get("task-1")).hasSize(1);
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
    void rejectsADaemonForAnUnknownTask() throws IOException {
        run("r1");

        assertThatThrownBy(() -> store.recordDaemon(daemon("r1", "task-9", "instance-1", 1)))
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
