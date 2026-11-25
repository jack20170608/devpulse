package io.github.devpulse.storage;

import java.io.IOException;
import java.util.List;

/**
 * Read side of the run store: the shapes a listing or dashboard needs.
 *
 * <p>Separate from {@link RunStore} on purpose. The write store answers "record this state change";
 * this side answers "show me a page of runs". They differ in filtering, paging and shape, so
 * sharing one interface would force the write model to grow UI fields.
 */
public interface RunQueries {

    /**
     * Runs newest first, optionally filtered by project and state.
     *
     * <p>A {@code null} filter matches everything, which is what a single-project database needs
     * and what a merged one would also want.
     *
     * @param project project filter, or {@code null} for every project
     * @param state run state filter, or {@code null} for every state
     * @param limit page size
     * @param offset rows to skip
     */
    List<RunSummary> runs(String project, String state, int limit, int offset) throws IOException;

    /** How many runs the same filter matches, for a pager. */
    long count(String project, String state) throws IOException;
}
