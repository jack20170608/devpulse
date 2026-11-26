package io.github.devpulse.storage;

/**
 * One dependency edge of a run: {@code dependentName} needs {@code prerequisiteName}.
 *
 * <p>The column names carry the direction, so no reader has to remember which side is which.
 */
public record TaskEdgeRecord(
        String runId, String dependentName, String prerequisiteName, int seq) {}
