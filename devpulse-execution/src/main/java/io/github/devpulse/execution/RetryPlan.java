package io.github.devpulse.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which nodes a retry must rerun and which it may reuse.
 *
 * <p>A node is rerun when it failed in the parent, when its execution fingerprint changed, when it
 * is a daemon (adoption is not implemented yet), or when it is downstream of any of those. Every
 * other node is reused. The decision is pure so it can be tested without running anything.
 */
public record RetryPlan(List<String> rerun, List<String> reuse, Map<String, String> reasons) {
    public RetryPlan {
        rerun = List.copyOf(rerun);
        reuse = List.copyOf(reuse);
        var ordered = new LinkedHashMap<String, String>();
        reasons.forEach(ordered::put);
        reasons = Collections.unmodifiableMap(ordered);
    }

    /**
     * @param parent the parent run's manifest
     * @param parentStates each parent node's recorded state, by name
     * @param fingerprints the current plan's fingerprints, by node name, in plan order
     * @param needs the current plan's dependency edges, by node name
     * @param daemons names of current nodes that are daemons
     * @param fromScratch when true, nothing is reused
     */
    public static RetryPlan compute(
            PlanManifest parent,
            Map<String, RunHistory.State> parentStates,
            Map<String, String> fingerprints,
            Map<String, List<String>> needs,
            Set<String> daemons,
            boolean fromScratch) {
        var reasons = new LinkedHashMap<String, String>();
        if (fromScratch) {
            fingerprints.keySet().forEach(name -> reasons.put(name, "from-scratch"));
        } else {
            for (var entry : fingerprints.entrySet()) {
                var name = entry.getKey();
                var previous = parent.nodes().get(name);
                if (previous == null) {
                    reasons.put(name, "new node");
                } else if (!previous.fingerprint().equals(entry.getValue())) {
                    reasons.put(name, "fingerprint changed");
                } else if (daemons.contains(name)) {
                    reasons.put(name, "daemon adoption not implemented");
                } else {
                    var state = parentStates.get(name);
                    if (state != RunHistory.State.SUCCESS) {
                        reasons.put(name, "parent " + (state == null ? "state unknown" : state));
                    }
                }
            }
        }
        var rerun = new LinkedHashSet<>(reasons.keySet());
        var dependents = dependents(needs);
        var queue = new ArrayList<>(rerun);
        while (!queue.isEmpty()) {
            var current = queue.remove(0);
            for (var dependent : dependents.getOrDefault(current, List.of())) {
                if (rerun.add(dependent)) {
                    reasons.putIfAbsent(dependent, "descendant of " + current);
                    queue.add(dependent);
                }
            }
        }
        var rerunNames = new ArrayList<String>();
        var reuseNames = new ArrayList<String>();
        for (var name : fingerprints.keySet()) {
            if (rerun.contains(name)) {
                rerunNames.add(name);
            } else {
                reuseNames.add(name);
            }
        }
        return new RetryPlan(rerunNames, reuseNames, reasons);
    }

    private static Map<String, List<String>> dependents(Map<String, List<String>> needs) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var entry : needs.entrySet()) {
            for (var dependency : entry.getValue()) {
                result.computeIfAbsent(dependency, key -> new ArrayList<>()).add(entry.getKey());
            }
        }
        return result;
    }
}
