package io.github.devpulse.storage;

/** Key of one reuse-index entry. */
public record TaskResultKey(String projectId, String name, String fingerprint) {}
