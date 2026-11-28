package io.github.devpulse.bash;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The run-record surface a Bash adapter needs while it executes tasks.
 *
 * <p>The adapter owns processes, logs and daemon state; it does not own how a run is persisted.
 * This interface is the seam between the two: {@code devpulse-execution} implements it with the
 * real run history, while the adapter only depends on these few operations. Keeping the seam here
 * lets the Bash backend be reused with a different recorder and keeps this module free of any
 * dependency on the execution layer.
 */
public interface TaskHistory {
    /** Identifier of the run these tasks belong to. */
    String id();

    /** Directory holding this run's logs and per-run files. */
    Path directory();

    /** Resolves a task name to the identifier used in the run record. */
    String taskId(String name);

    /** Records that a task started. */
    void started(String name) throws IOException;

    /** Opens a task log; {@code append} keeps output from an earlier attempt. */
    BufferedWriter openLog(String name, boolean append) throws IOException;

    /** Records that a task's process exited with {@code exitCode}. */
    void processExited(String name, int exitCode) throws IOException;
}
