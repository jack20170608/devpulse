package io.github.devpulse.storage;

import java.time.Instant;

/** The payload of one workflow revision, retained after the head moves on. */
public record WorkflowRevisionRecord(
        String projectId,
        String workflowId,
        int revision,
        String content,
        String contentHash,
        Instant createdAt) {}
