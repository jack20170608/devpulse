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
 * @param health the last recorded readiness state, or {@code null} if never checked
 * @param healthError why the last check failed, or {@code null}
 * @param readyAt when readiness was first observed, or {@code null} if it never was
 * @param stoppedAt when a stop was observed, or {@code null} if it never was
 * @param serverBootId the server boot that started this process, or null for a local run
 */
public record DaemonRecord(
        String runId,
        String taskId,
        String instanceId,
        int attempt,
        String name,
        String bash,
        long pid,
        Instant processStartedAt,
        String readyCommand,
        String stopCommand,
        long stopTimeoutNanos,
        long intervalNanos,
        long timeoutNanos,
        long checkTimeoutNanos,
        String health,
        String healthError,
        Instant checkedAt,
        Instant readyAt,
        Instant stoppedAt,
        String serverBootId) {}
