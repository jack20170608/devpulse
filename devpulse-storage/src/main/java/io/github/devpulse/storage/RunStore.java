package io.github.devpulse.storage;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable store for projects, runs, tasks, daemon instances and the cross-run reuse index.
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
     * Record a project, a run and its tasks in one transaction, so no reader can observe a run
     * without its project or its tasks.
     *
     * <p>The project is upserted first: a run cannot exist without one, and a second run in the
     * same project only refreshes its {@code last_seen_at}.
     */
    void begin(ProjectRecord project, RunRecord run, List<TaskRecord> tasks) throws IOException;

    /** Replace one task's mutable state. Identity, fingerprint and order do not change. */
    void updateTask(TaskRecord task) throws IOException;

    /** Replace one task's execution state only. */
    void updateTaskState(TaskRecord task) throws IOException;

    /** Mark the run finished. */
    void finishRun(String runId, String state, Instant finishedAt) throws IOException;

    Optional<RunRecord> run(String runId) throws IOException;

    /** Runs of one project, newest first; a {@code null} project means every run. */
    List<RunRecord> runs(String projectId, int limit) throws IOException;

    Optional<ProjectRecord> project(String id) throws IOException;

    /** One run's tasks in their recorded order. */
    List<TaskRecord> tasks(String runId) throws IOException;

    /** Tasks of several runs, grouped by run id and ordered within each run. */
    Map<String, List<TaskRecord>> tasks(List<String> runIds) throws IOException;

    /** The reuse index lookup: a finished result for this exact node in this project. */
    Optional<TaskResult> successfulResult(String projectId, String name, String fingerprint)
            throws IOException;

    /** Publish a finished result to the reuse index, replacing any earlier entry for the key. */
    void recordResult(TaskResult result) throws IOException;

    /** Insert or replace one daemon instance record, keyed by run, task and instance. */
    void recordDaemon(DaemonRecord record) throws IOException;

    /** One run's daemon instances for a task, ordered by attempt and process start. */
    List<DaemonRecord> daemons(String runId, String taskId) throws IOException;

    /** Every daemon instance of a run, grouped by task id and ordered by attempt. */
    Map<String, List<DaemonRecord>> daemons(String runId) throws IOException;

    /**
     * Record a readiness observation on the task's latest daemon instance.
     *
     * <p>The check happens per task, while health lives per instance, so the store resolves the
     * instance rather than making the caller track it.
     */
    void recordDaemonHealth(
            String runId, String taskId, String health, String error, Instant checkedAt)
            throws IOException;

    @Override
    void close() throws IOException;
}
