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
 */
public record PlanManifest(
        String target,
        String project,
        int parallelism,
        String bash,
        String toolVersion,
        String parentRunId,
        Map<String, Node> nodes) {
    /** One node's execution-relevant definition, fingerprinted. */
    public record Node(String name, String fingerprint, List<String> needs) {
        public Node {
            needs = List.copyOf(needs);
        }
    }

    public PlanManifest {
        var copy = new LinkedHashMap<String, Node>();
        nodes.forEach(copy::put);
        nodes = Collections.unmodifiableMap(copy);
    }
}
