package io.github.devpulse.storage;

import java.time.Instant;

/**
 * One started daemon process.
 *
 * <p>Durations are stored as nanoseconds because that is what the readiness state machine compares;
 * converting through a human-readable duration on every read would only add rounding.
 *
 * @param taskId the per-run node id that owns this instance
 * @param processStartedAt start time of the process, used to tell a live process from a reused pid
 * @param readyAt when readiness was first observed, or {@code null} if it never was
 * @param stoppedAt when a stop was observed, or {@code null} if it never was
 */
public record DaemonRecord(
        String runId,
        String taskId,
        String instanceId,
        int attempt,
        String name,
        String project,
        String bash,
        long pid,
        Instant processStartedAt,
        String readyCommand,
        String stopCommand,
        long stopTimeoutNanos,
        long intervalNanos,
        long timeoutNanos,
        long checkTimeoutNanos,
        Instant readyAt,
        Instant stoppedAt) {}
