package io.github.devpulse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpulse.core.bash.PlanManifest;
import io.github.devpulse.core.bash.RunHistory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the retry decision table. Every case here is a promise the CLI makes: nodes that were not
 * affected must be reused, and everything a change touches must rerun.
 *
 * <p>Inputs are ordered maps on purpose: the plan's node order is the order the CLI reports, and an
 * unordered map would make these assertions depend on hash layout.
 */
class RetryPlanTest {
    private static Map<String, String> ordered(String... namesAndFingerprints) {
        var map = new LinkedHashMap<String, String>();
        for (var index = 0; index < namesAndFingerprints.length; index += 2) {
            map.put(namesAndFingerprints[index], namesAndFingerprints[index + 1]);
        }
        return map;
    }

    private static PlanManifest parent(Map<String, String> fingerprints) {
        var nodes = new LinkedHashMap<String, PlanManifest.Node>();
        fingerprints.forEach((name, fingerprint) ->
                nodes.put(name, new PlanManifest.Node(name, fingerprint, List.of())));
        return new PlanManifest("dev", "project", 4, "bash", "1.0", null, nodes);
    }

    private static Map<String, RunHistory.State> allSuccess(String... names) {
        var states = new LinkedHashMap<String, RunHistory.State>();
        for (var name : names) {
            states.put(name, RunHistory.State.SUCCESS);
        }
        return states;
    }

    @Test
    void reusesEverythingThatStillMatches() {
        var fingerprints = ordered("a", "1", "b", "1");

        var plan =
                RetryPlan.compute(
                        parent(fingerprints),
                        allSuccess("a", "b"),
                        fingerprints,
                        Map.of("a", List.<String>of(), "b", List.of("a")),
                        Set.of(),
                        false);

        assertThat(plan.reuse()).containsExactly("a", "b");
        assertThat(plan.rerun()).isEmpty();
        assertThat(plan.reasons()).isEmpty();
    }

    @Test
    void rerunsAFailedNodeAndItsDescendants() {
        var fingerprints = ordered("a", "1", "b", "1", "c", "1");
        var states = allSuccess("a");
        states.put("b", RunHistory.State.FAILED);
        states.put("c", RunHistory.State.SKIPPED);

        var plan =
                RetryPlan.compute(
                        parent(fingerprints),
                        states,
                        fingerprints,
                        Map.of("a", List.<String>of(), "b", List.of("a"), "c", List.of("b")),
                        Set.of(),
                        false);

        assertThat(plan.rerun()).containsExactly("b", "c");
        assertThat(plan.reuse()).containsExactly("a");
        assertThat(plan.reasons()).containsEntry("b", "parent FAILED");
        assertThat(plan.reasons()).containsEntry("c", "parent SKIPPED");
    }

    @Test
    void rerunsEverythingDownstreamOfAChangedFingerprint() {
        var fingerprints = ordered("a", "2", "b", "1");

        var plan =
                RetryPlan.compute(
                        parent(ordered("a", "1", "b", "1")),
                        allSuccess("a", "b"),
                        fingerprints,
                        Map.of("a", List.<String>of(), "b", List.of("a")),
                        Set.of(),
                        false);

        assertThat(plan.rerun()).containsExactly("a", "b");
        assertThat(plan.reasons()).containsEntry("a", "fingerprint changed");
        assertThat(plan.reasons()).containsEntry("b", "descendant of a");
    }

    @Test
    void rerunsANodeThatDidNotExistBefore() {
        var fingerprints = ordered("a", "1", "b", "1");

        var plan =
                RetryPlan.compute(
                        parent(ordered("a", "1")),
                        allSuccess("a"),
                        fingerprints,
                        Map.of("a", List.<String>of(), "b", List.of("a")),
                        Set.of(),
                        false);

        assertThat(plan.rerun()).containsExactly("b");
        assertThat(plan.reasons()).containsEntry("b", "new node");
    }

    @Test
    void rerunsADaemonBecauseAdoptionIsNotImplemented() {
        var fingerprints = ordered("server", "1");

        var plan =
                RetryPlan.compute(
                        parent(fingerprints),
                        allSuccess("server"),
                        fingerprints,
                        Map.of("server", List.<String>of()),
                        Set.of("server"),
                        false);

        assertThat(plan.rerun()).containsExactly("server");
        assertThat(plan.reasons().get("server")).contains("daemon");
    }

    @Test
    void rerunsEverythingFromScratch() {
        var fingerprints = ordered("a", "1", "b", "1");

        var plan =
                RetryPlan.compute(
                        parent(fingerprints),
                        allSuccess("a", "b"),
                        fingerprints,
                        Map.of("a", List.<String>of(), "b", List.of("a")),
                        Set.of(),
                        true);

        assertThat(plan.rerun()).containsExactly("a", "b");
        assertThat(plan.reuse()).isEmpty();
        assertThat(plan.reasons()).containsEntry("a", "from-scratch");
    }

    @Test
    void reportsAParentNodeWithNoRecordedState() {
        var fingerprints = ordered("a", "1");

        var plan =
                RetryPlan.compute(
                        parent(fingerprints),
                        Map.of(),
                        fingerprints,
                        Map.of("a", List.<String>of()),
                        Set.of(),
                        false);

        assertThat(plan.reasons()).containsEntry("a", "parent state unknown");
    }
}
