package io.github.devpulse.storage;

/** A workflow is identified by its project and its file stem. */
public record WorkflowKey(String projectId, String workflowId) {}
