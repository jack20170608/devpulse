package io.github.devpulse.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable inputs of a run, written once when the run starts and never rewritten.
 *
 * <p>Execution state lives in the project's run database and changes constantly; this manifest does
 * not. A retry compares a later configuration against these fingerprints to decide which nodes are
 * still valid, so keeping them in a separate file means a state update can never corrupt the
 * evidence a retry depends on.
 *
 * <p>The optional {@link WorkflowRef} names the workflow file that produced the plan and, when the
 * run came from the server, the exact revision it executed. A run made straight from a local file
 * carries the content hash but no revision, because no server version was involved.
 */
public record PlanManifest(
        String target,
        String project,
        int parallelism,
        String bash,
        String toolVersion,
        String parentRunId,
        Map<String, Node> nodes,
        WorkflowRef workflow) {

    /** One node's execution-relevant definition, fingerprinted. */
    public record Node(String name, String fingerprint, List<String> needs) {
        public Node {
            needs = List.copyOf(needs);
        }
    }

    /**
     * The workflow a run belongs to.
     *
     * @param id the workflow id (the file stem)
     * @param path the path as the user gave it, relative to the project when possible
     * @param revision the server revision that was executed, or {@code null} for a local-only run
     * @param hash the SHA-256 of the file content at submission time
     */
    public record WorkflowRef(String id, String path, Integer revision, String hash) {}

    public PlanManifest {
        var copy = new LinkedHashMap<String, Node>();
        nodes.forEach(copy::put);
        nodes = Collections.unmodifiableMap(copy);
    }

    /** A plan recorded before workflow identity existed, or one with no workflow file behind it. */
    public PlanManifest(
            String target,
            String project,
            int parallelism,
            String bash,
            String toolVersion,
            String parentRunId,
            Map<String, Node> nodes) {
        this(target, project, parallelism, bash, toolVersion, parentRunId, nodes, null);
    }
}
