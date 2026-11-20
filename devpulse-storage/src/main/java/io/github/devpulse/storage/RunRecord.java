package io.github.devpulse.storage;

import java.time.Instant;

/**
 * One recorded execution of a target.
 *
 * @param executorStartedAt start time of the executor process, used to tell a live executor from a
 *     reused pid when recovering an interrupted run
 * @param bash the Bash executable used, recorded so a replay can report an environment change
 * @param parentRunId the run this one was derived from, or {@code null} for a first execution
 */
public record RunRecord(
        String id,
        String project,
        String target,
        int parallelism,
        String state,
        long executorPid,
        Instant executorStartedAt,
        Instant startedAt,
        Instant finishedAt,
        String bash,
        String toolVersion,
        String parentRunId) {}
