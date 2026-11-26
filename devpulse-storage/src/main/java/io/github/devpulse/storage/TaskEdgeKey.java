package io.github.devpulse.storage;

/** Key of one dependency edge. */
public record TaskEdgeKey(String runId, String dependentName, String prerequisiteName) {}
