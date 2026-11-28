package io.github.devpulse.storage;

import java.time.Instant;

/**
 * One recorded execution of a target.
 *
 * @param projectId the project this run belongs to
 * @param requestedBy who submitted the run ({@code user@host} plus working directory), or null
 * @param queuedAt when the run was accepted; {@code startedAt} is when execution began
 * @param workerPid the process that executed the run, or null while queued
 * @param workerStartedAt start time of the worker process, used to tell a live worker from a reused
 *     pid after a server restart
 * @param serverBootId the server boot that owns this run, or null for a local run
 * @param bash the Bash executable used, recorded so a replay can report an environment change
 * @param parentRunId the run this one was derived from, or {@code null} for a first execution
 * @param workflowId the workflow file stem, or null for a run with no workflow behind it
 * @param workflowPath the path the user gave, relative to the project when possible
 * @param workflowRevision the server revision executed, or null for a local-only run
 * @param workflowHash the SHA-256 of the workflow content at submission time
 */
public record RunRecord(
        String id,
        String projectId,
        String target,
        int parallelism,
        String state,
        String requestedBy,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long workerPid,
        Instant workerStartedAt,
        String serverBootId,
        String bash,
        String toolVersion,
        String parentRunId,
        String workflowId,
        String workflowPath,
        Integer workflowRevision,
        String workflowHash) {

    /** A run recorded before workflow identity existed. */
    public RunRecord(
            String id,
            String projectId,
            String target,
            int parallelism,
            String state,
            String requestedBy,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Long workerPid,
            Instant workerStartedAt,
            String serverBootId,
            String bash,
            String toolVersion,
            String parentRunId) {
        this(
                id,
                projectId,
                target,
                parallelism,
                state,
                requestedBy,
                queuedAt,
                startedAt,
                finishedAt,
                workerPid,
                workerStartedAt,
                serverBootId,
                bash,
                toolVersion,
                parentRunId,
                null,
                null,
                null,
                null);
    }
}
