package io.github.devpulse.storage;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable store for runs, tasks, daemon instances and the cross-run reuse index.
 *
 * <p>The interface names no SQL and no engine type, so an implementation can be replaced without
 * touching callers. JDBC failures surface as {@link IOException} because every caller already
 * treats storage failure as an I/O failure.
 */
public interface RunStore extends AutoCloseable {
    /** Create or upgrade the schema. Safe to call on every open. */
    void migrate() throws IOException;

    /** The schema version currently applied. */
    int schemaVersion() throws IOException;

    /**
     * Record a run and its tasks in one transaction, so no reader can observe a run without tasks.
     *
     * <p>Each task's {@link TaskRecord#sortOrder()} decides its stored position; the caller owns
     * that number rather than relying on the list order.
     */
    void begin(RunRecord run, List<TaskRecord> tasks) throws IOException;

    /** Replace one task's mutable state. Identity, fingerprint and order do not change. */
    void updateTask(TaskRecord task) throws IOException;

    /**
     * Replace one task's execution state only.
     *
     * <p>Identity, fingerprint and stored order are left untouched, so a caller that does not know
     * the fingerprint cannot erase it by passing {@code null}.
     */
    void updateTaskState(TaskRecord task) throws IOException;

    /** Mark the run finished. */
    void finishRun(String runId, String state, Instant finishedAt) throws IOException;

    Optional<RunRecord> run(String runId) throws IOException;

    /** Runs of one project, newest first; a {@code null} project means every run. */
    List<RunRecord> runs(String project, int limit) throws IOException;

    /** One run's tasks in their recorded order. */
    List<TaskRecord> tasks(String runId) throws IOException;

    /** The reuse index lookup: a finished result for this exact node definition. */
    Optional<TaskResult> successfulResult(String name, String fingerprint) throws IOException;

    /** Publish a finished result to the reuse index, replacing any earlier entry for the key. */
    void recordResult(TaskResult result) throws IOException;

    @Override
    void close() throws IOException;
}
