package io.github.devpulse.storage;

/** One immutable version of a workflow, kept so a run can name the revision it executed. */
public record WorkflowRevisionKey(String projectId, String workflowId, int revision) {}
