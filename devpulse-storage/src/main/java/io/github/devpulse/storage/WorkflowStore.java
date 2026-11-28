package io.github.devpulse.storage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The server's workflow registry: the source of truth a project's local file mirrors.
 *
 * <p>Every push creates an immutable revision and moves the head. A push that names a stale base
 * revision is refused rather than silently overwriting a newer version; the caller decides how to
 * show the conflict and which side to keep.
 */
public interface WorkflowStore {
    /** The largest workflow payload accepted, matching the YAML parser's limit. */
    int MAX_CONTENT_BYTES = 1024 * 1024;

    /**
     * Store one new revision of a workflow.
     *
     * @param baseRevision the revision the caller edited from, or {@code null} to overwrite blindly
     * @throws WorkflowConflictException when {@code baseRevision} is stale
     */
    WorkflowRecord push(
            ProjectRecord project,
            String workflowId,
            String name,
            String content,
            Integer baseRevision)
            throws IOException;

    Optional<WorkflowRecord> findWorkflow(String projectId, String workflowId) throws IOException;

    List<WorkflowRecord> listWorkflows(String projectId) throws IOException;

    Optional<WorkflowRevisionRecord> findRevision(String projectId, String workflowId, int revision)
            throws IOException;

    List<WorkflowRevisionRecord> listRevisions(String projectId, String workflowId, int limit)
            throws IOException;
}
