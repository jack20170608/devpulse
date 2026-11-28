package io.github.devpulse.storage;

/**
 * Raised when a push was based on a revision that is no longer the head.
 *
 * <p>Carries the current head so the caller can show both sides and offer {@code --force} rather
 * than just saying "conflict".
 */
public final class WorkflowConflictException extends RuntimeException {
    private final WorkflowRecord current;

    public WorkflowConflictException(WorkflowRecord current) {
        super(
                "Workflow "
                        + current.workflowId()
                        + " is at revision "
                        + current.revision()
                        + ", not the revision the push was based on.");
        this.current = current;
    }

    /** The revision the server currently holds. */
    public WorkflowRecord current() {
        return current;
    }
}
