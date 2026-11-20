package io.github.devpulse.storage;

import java.time.Instant;

/**
 * A completed node result, keyed by task name and execution fingerprint.
 *
 * <p>This is the reuse index: a node whose key already has a finished result can be skipped
 * wherever it appears, without any link to a particular parent run.
 */
public record TaskResult(String name, String fingerprint, String runId, Instant finishedAt) {}
