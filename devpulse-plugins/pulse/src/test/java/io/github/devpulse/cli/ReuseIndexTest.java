package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.dag.DagGraph;
import io.github.devpulse.core.dag.TaskKey;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the reuse index: a successful node is published under its fingerprint, and a failed one is
 * not, because the index is what a later cached run consults.
 */
class ReuseIndexTest {
    @TempDir Path directory;

    private RunHistory.Writer begin() throws IOException {
        var graph = DagGraph.builder();
        graph.node(TaskKey.of("build", Integer.class), List.<TaskKey<?>>of());
        graph.node(TaskKey.of("test", ProcessHandle.class), List.<TaskKey<?>>of());
        return new RunHistory(directory).begin("dev", graph.build(), 1);
    }

    @Test
    void publishesASuccessfulNode() throws IOException {
        try (var history = begin()) {
            history.started("build");
            history.completed("build", RunHistory.State.SUCCESS, 0, null, Instant.now());
            history.ended(RunHistory.State.SUCCESS);

            PulseCommand.recordReuse(history, Map.of("build", "fp-build"));

            var found = history.store().successfulResult("build", "fp-build").orElseThrow();
            assertThat(found.runId()).isEqualTo(history.id());
        }
    }

    @Test
    void ignoresAFailedNode() throws IOException {
        try (var history = begin()) {
            history.started("build");
            history.completed("build", RunHistory.State.FAILED, 3, "boom", Instant.now());
            history.ended(RunHistory.State.FAILED);

            PulseCommand.recordReuse(history, Map.of("build", "fp-build"));

            assertThat(history.store().successfulResult("build", "fp-build")).isEmpty();
        }
    }

    @Test
    void ignoresANodeWithNoFingerprint() throws IOException {
        try (var history = begin()) {
            history.started("build");
            history.completed("build", RunHistory.State.SUCCESS, 0, null, Instant.now());
            history.ended(RunHistory.State.SUCCESS);

            PulseCommand.recordReuse(history, Map.of());

            assertThat(history.store().successfulResult("build", "fp-build")).isEmpty();
        }
    }
}
