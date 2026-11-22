package io.github.devpulse.storage;

/** Key of a {@code daemon_instance} row. */
public record DaemonKey(String runId, String taskId, String instanceId) {}
