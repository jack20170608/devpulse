package io.github.devpulse.storage;

import java.time.Instant;

/**
 * A completed node result, keyed by project, task name and execution fingerprint.
 *
 * <p>This is the reuse index. The project is part of the key because the same task name and
 * fingerprint mean different things in different projects.
 */
public record TaskResult(
        String projectId, String name, String fingerprint, String runId, Instant finishedAt) {}
