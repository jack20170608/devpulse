package io.github.devpulse.storage;

/** Key of a {@code task} row: the owning run plus the task name. */
public record TaskKey(String runId, String name) {}
