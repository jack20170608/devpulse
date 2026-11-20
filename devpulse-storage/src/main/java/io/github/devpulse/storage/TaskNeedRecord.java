package io.github.devpulse.storage;

/** One dependency edge of a run: {@code taskName} needs {@code needName}. */
public record TaskNeedRecord(String runId, String taskName, String needName, int sortOrder) {}
