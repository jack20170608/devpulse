package io.github.devpulse.storage;

/** Key of a {@code task_need} row. */
public record TaskNeedKey(String runId, String taskName, String needName) {}
