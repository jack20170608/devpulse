package io.github.devpulse.storage;

import java.time.Instant;

/**
 * One row of a run list, shaped for a caller that renders a history table.
 *
 * <p>This is a read model, not the stored record: it carries the task tallies the UI shows and
 * omits the fields the write path needs. Keeping it separate means a new list column never changes
 * {@link RunRecord}, and a change to the write model never silently changes what a list shows.
 *
 * @param taskCount tasks recorded for the run
 * @param succeeded tasks that finished successfully
 * @param failed tasks that failed or timed out
 */
public record RunSummary(
        String id,
        String target,
        String state,
        Instant startedAt,
        Instant finishedAt,
        int taskCount,
        int succeeded,
        int failed) {}
