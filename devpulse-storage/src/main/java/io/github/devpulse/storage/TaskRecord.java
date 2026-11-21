package io.github.devpulse.storage;

import java.time.Instant;
import java.util.List;

/**
 * One node of a run.
 *
 * <p>The record models the stored row, including {@code sortOrder}: the read order of a run's tasks
 * is part of the data, so a caller writing through a DAO supplies it rather than relying on an
 * implicit position.
 *
 * @param fingerprint the execution fingerprint recorded when the node started
 * @param taskId the opaque per-run node id, used for log files and daemon instance directories; it
 *     is unique within a run but may repeat across runs
 * @param sortOrder this task's position within its run
 * @param needs this node's prerequisites, in declaration order; stored in {@code task_need}, so a
 *     DAO read returns it empty and the store joins it back in
 * @param reusedFrom the run this result was inherited from, or {@code null} when it ran here
 */
public record TaskRecord(
        String runId,
        String name,
        String taskId,
        String fingerprint,
        String state,
        Integer exitCode,
        String error,
        Instant startedAt,
        Instant finishedAt,
        String logPath,
        String reusedFrom,
        String daemonHealth,
        String daemonError,
        Instant daemonCheckedAt,
        int sortOrder,
        List<String> needs) {
    public TaskRecord {
        needs = List.copyOf(needs);
    }
}
