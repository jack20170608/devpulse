package io.github.devpulse.storage;

import java.time.Instant;
import java.util.List;

/**
 * One node of a run.
 *
 * <p>The record models the stored row. Dependency edges live in {@code task_edge}, not in a column,
 * so a row read here reports an empty {@code needs} list; the store joins them back in when the
 * graph matters.
 *
 * @param fingerprint the execution fingerprint recorded when the node started
 * @param taskId the opaque per-run node id, used for log files and daemon instance directories; it
 *     is unique within a run but may repeat across runs
 * @param seq this task's position within its run
 * @param errorKind the class of failure, so a process exit code and an adapter exception are
 *     distinguishable without parsing {@code error}
 * @param needs this node's prerequisites, in declaration order; not a column
 * @param reusedFrom the run this result was inherited from, or {@code null} when it ran here
 */
public record TaskRecord(
        String runId,
        String name,
        String taskId,
        int seq,
        String fingerprint,
        String state,
        Integer exitCode,
        String error,
        String errorKind,
        Instant startedAt,
        Instant finishedAt,
        String logPath,
        String reusedFrom,
        List<String> needs) {
    public TaskRecord {
        needs = List.copyOf(needs);
    }
}
