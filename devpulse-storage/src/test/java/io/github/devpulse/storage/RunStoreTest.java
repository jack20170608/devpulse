package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the storage contract: schema migration, transactional run creation, ordered task reads,
 * and the reuse index that replaces parent-run-based retry.
 */
class RunStoreTest {
    private static final Instant BASE = Instant.parse("2026-11-20T00:00:00Z");

    private RunStore store;
    private String url;

    @BeforeEach
    void open() throws IOException {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        store = JdbiRunStore.open(url);
        store.migrate();
    }

    @AfterEach
    void close() throws IOException {
        store.close();
    }

    private static RunRecord run(String id, String project, Instant startedAt, String state) {
        return new RunRecord(
                id,
                project,
                "dev",
                4,
                state,
                null,
                startedAt,
                startedAt,
                null,
                1234L,
                startedAt,
                null,
                "/bin/bash",
                "1.0",
                null);
    }

    private static TaskRecord task(String runId, String name, String state, List<String> needs) {
        return new TaskRecord(
                runId,
                name,
                name + "-id",
                0,
                "fp-" + name,
                state,
                null,
                null,
                null,
                BASE,
                null,
                "logs/" + name + ".log",
                null,
                needs);
    }

    /** A task whose stored order is set explicitly, so name order cannot mask the contract. */
    private static TaskRecord orderedTask(String runId, String name, int seq) {
        return new TaskRecord(
                runId,
                name,
                name + "-id",
                seq,
                "fp-" + name,
                "PENDING",
                null,
                null,
                null,
                BASE,
                null,
                "logs/" + name + ".log",
                null,
                List.of());
    }

    /** Begin a run, creating the project row it points at. */
    private void begin(RunRecord run, List<TaskRecord> tasks) throws IOException {
        store.begin(
                new ProjectRecord(
                        run.projectId(), "/" + run.projectId(), run.projectId(), BASE, BASE),
                run,
                tasks);
    }

    @Test
    void ordersTasksByRecordedSortOrderNotByName() throws IOException {
        begin(
                run("r1", "proj", BASE, "RUNNING"),
                List.of(orderedTask("r1", "alpha", 1), orderedTask("r1", "zebra", 0)));

        var tasks = store.tasks("r1");

        assertThat(tasks).extracting(TaskRecord::name).containsExactly("zebra", "alpha");
        assertThat(tasks.getFirst().seq()).isZero();
    }

    @Test
    void migratesAndReportsItsVersion() throws IOException {
        assertThat(store.schemaVersion()).isEqualTo(Schema.VERSION);
        store.migrate();
        assertThat(store.schemaVersion()).isEqualTo(Schema.VERSION);
    }

    /**
     * The version shortcut must not hide a store written by a newer build: skipping the migration
     * there would let this build read a schema it does not understand.
     */
    @Test
    void stillRejectsAStoreNewerThanThisBuild() throws IOException {
        try (var handle = Jdbi.create(url, "sa", "").open()) {
            handle.execute(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                    Schema.VERSION + 1,
                    OffsetDateTime.now(ZoneOffset.UTC));
        }

        assertThatThrownBy(() -> store.migrate())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("newer");
    }

    @Test
    void listsTasksOfSeveralRunsInOneRead() throws IOException {
        begin(
                run("r1", "proj", BASE, "SUCCESS"),
                List.of(
                        task("r1", "prep", "SUCCESS", List.of()),
                        task("r1", "build", "SUCCESS", List.of("prep")),
                        task("r1", "test", "SUCCESS", List.of("build"))));
        begin(
                run("r2", "proj", BASE.plusSeconds(60), "FAILED"),
                List.of(
                        task("r2", "build", "SUCCESS", List.of()),
                        task("r2", "deploy", "FAILED", List.of("build"))));

        var byRun = store.tasks(List.of("r1", "r2"));

        assertThat(byRun).containsOnlyKeys("r1", "r2");
        assertThat(byRun.get("r1"))
                .extracting(TaskRecord::name)
                .containsExactly("build", "prep", "test");
        assertThat(byRun.get("r1").getFirst().needs()).containsExactly("prep");
        assertThat(byRun.get("r2").getFirst().needs()).isEmpty();
        assertThat(byRun.get("r2").getLast().needs()).containsExactly("build");
        assertThat(store.tasks(List.of())).isEmpty();
    }

    @Test
    void recordsARunWithItsTasksAndDependencies() throws IOException {
        begin(
                run("r1", "proj", BASE, "RUNNING"),
                List.of(
                        task("r1", "build", "PENDING", List.of()),
                        task("r1", "test", "PENDING", List.of("build"))));

        var recorded = store.run("r1").orElseThrow();
        assertThat(recorded.target()).isEqualTo("dev");
        assertThat(recorded.workerPid()).isEqualTo(1234L);
        assertThat(recorded.bash()).isEqualTo("/bin/bash");

        var tasks = store.tasks("r1");
        assertThat(tasks).extracting(TaskRecord::name).containsExactly("build", "test");
        assertThat(tasks.get(1).needs()).containsExactly("build");
    }

    @Test
    void leavesNoRunBehindWhenATaskCannotBeInserted() throws IOException {
        var duplicated =
                List.of(
                        task("r1", "build", "PENDING", List.of()),
                        task("r1", "build", "PENDING", List.of()));

        assertThatThrownBy(() -> begin(run("r1", "proj", BASE, "RUNNING"), duplicated))
                .isInstanceOf(IOException.class);
        assertThat(store.run("r1")).isEmpty();
    }

    @Test
    void updatesATaskAndFinishesTheRun() throws IOException {
        begin(
                run("r1", "proj", BASE, "RUNNING"),
                List.of(task("r1", "build", "PENDING", List.of())));

        store.updateTask(
                new TaskRecord(
                        "r1",
                        "build",
                        "build-id",
                        0,
                        "fp-build",
                        "SUCCESS",
                        0,
                        null,
                        null,
                        BASE,
                        BASE.plusSeconds(2),
                        "logs/build.log",
                        null,
                        List.of()));
        var finished = store.tasks("r1").get(0);
        assertThat(finished.state()).isEqualTo("SUCCESS");
        assertThat(finished.exitCode()).isZero();
        assertThat(finished.finishedAt()).isEqualTo(BASE.plusSeconds(2));

        store.finishRun("r1", "SUCCESS", BASE.plusSeconds(3));
        assertThat(store.run("r1").orElseThrow().finishedAt()).isEqualTo(BASE.plusSeconds(3));
    }

    @Test
    void listsRunsOfAProjectNewestFirst() throws IOException {
        begin(run("r1", "proj", BASE, "SUCCESS"), List.of());
        begin(run("r2", "proj", BASE.plusSeconds(60), "SUCCESS"), List.of());
        begin(run("r3", "other", BASE.plusSeconds(120), "SUCCESS"), List.of());

        assertThat(store.runs("proj", 10)).extracting(RunRecord::id).containsExactly("r2", "r1");
        assertThat(store.runs(null, 10))
                .extracting(RunRecord::id)
                .containsExactly("r3", "r2", "r1");
    }

    @Test
    void exposesAFinishedResultForReuse() throws IOException {
        assertThat(store.successfulResult("proj", "build", "fp-build")).isEmpty();
        begin(run("r1", "proj", BASE, "SUCCESS"), List.of());

        store.recordResult(new TaskResult("proj", "build", "fp-build", "r1", BASE.plusSeconds(2)));

        var result = store.successfulResult("proj", "build", "fp-build").orElseThrow();
        assertThat(result.runId()).isEqualTo("r1");
        assertThat(result.finishedAt()).isEqualTo(BASE.plusSeconds(2));
        assertThat(store.successfulResult("proj", "build", "other-fingerprint")).isEmpty();
    }

    @Test
    void replacesAReuseEntryForTheSameKey() throws IOException {
        begin(run("r1", "proj", BASE, "SUCCESS"), List.of());
        begin(run("r2", "proj", BASE.plusSeconds(1), "SUCCESS"), List.of());
        store.recordResult(new TaskResult("proj", "build", "fp", "r1", BASE));
        store.recordResult(new TaskResult("proj", "build", "fp", "r2", BASE.plusSeconds(10)));

        var result = store.successfulResult("proj", "build", "fp").orElseThrow();
        assertThat(result.runId()).isEqualTo("r2");
        assertThat(result.finishedAt()).isEqualTo(BASE.plusSeconds(10));
    }

    @Test
    void rejectsADuplicateRunId() throws IOException {
        begin(run("r1", "proj", BASE, "SUCCESS"), List.of());

        assertThatThrownBy(() -> begin(run("r1", "proj", BASE, "SUCCESS"), List.of()))
                .isInstanceOf(IOException.class);
    }
}
