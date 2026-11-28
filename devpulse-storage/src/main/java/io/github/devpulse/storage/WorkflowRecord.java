package io.github.devpulse.storage;

import java.time.Instant;

/**
 * The current version of one workflow, as the server knows it.
 *
 * <p>The server is the source of truth; a project's local file is a twin that can be pulled or
 * pushed. {@code revision} is the server's monotonic version, while {@code contentHash} identifies
 * the exact bytes so a client can tell "same revision" from "different content".
 */
public record WorkflowRecord(
        String projectId,
        String workflowId,
        String name,
        String content,
        String contentHash,
        int revision,
        Instant createdAt,
        Instant updatedAt) {}
