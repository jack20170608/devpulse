package io.github.devpulse.cli;

/**
 * Process exit codes that a caller can act on without parsing stderr.
 *
 * <p>The code identifies the class of failure, so an automated caller can decide what to fix: the
 * configuration, the dependency graph, the requested target, the environment, or the task itself.
 * Codes are part of the CLI contract and must not be renumbered.
 */
public final class ExitCodes {
    /** Every requested node succeeded. */
    public static final int OK = 0;

    /** The command line itself was unusable, such as a non-positive {@code --jobs}. */
    public static final int USAGE = 2;

    /** The configuration could not be read, parsed, or validated as a whole. */
    public static final int CONFIG_INVALID = 10;

    /** The configuration is well formed, but the dependency graph or a field value is wrong. */
    public static final int CONFIG_GRAPH = 11;

    /** The requested target does not exist, or is ambiguous. */
    public static final int TARGET_INVALID = 12;

    /** A required external capability is missing, such as Bash. */
    public static final int ENVIRONMENT = 20;

    /** The requested operation was refused while the state it needs is unavailable. */
    public static final int RETRY_REFUSED = 13;

    /** At least one task ran and failed, or the run state is not success. */
    public static final int TASK_FAILED = 30;

    /** A daemon never became ready before its startup timeout. */
    public static final int DAEMON_NOT_READY = 31;

    /** A daemon that had become ready later became unavailable or exited. */
    public static final int DAEMON_LOST = 32;

    /** Run records, logs, or another local infrastructure could not be written. */
    public static final int INFRASTRUCTURE = 40;

    /** The coordinating thread was interrupted. */
    public static final int INTERRUPTED = 130;

    private ExitCodes() {}
}
