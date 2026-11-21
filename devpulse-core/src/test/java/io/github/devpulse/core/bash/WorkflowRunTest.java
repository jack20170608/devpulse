package io.github.devpulse.core.bash;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagRun;
import io.github.devpulse.core.dag.TaskKey;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the decision table that turns daemon health into a classifiable run outcome. Health alone
 * is ambiguous for {@code UNKNOWN}, so the startup node's state is part of the contract.
 */
class WorkflowRunTest {
    private static final TaskKey<ProcessHandle> KEY = TaskKey.of("daemon", ProcessHandle.class);

    private static WorkflowRun.DaemonOutcome outcome(
            DagRun.NodeState nodeState, DagDaemon.Health health) {
        return WorkflowRun.outcome(
                Map.of(
                        KEY,
                        new DagRun.NodeResult(
                                nodeState, 1, null, null, Instant.EPOCH, Instant.EPOCH)),
                Map.of(KEY, new DagRun.DaemonResult(health, null, Instant.EPOCH)));
    }

    @Test
    void reportsNothingWithoutDaemons() {
        assertThat(WorkflowRun.outcome(Map.of(), Map.of()))
                .isEqualTo(WorkflowRun.DaemonOutcome.NONE);
    }

    @Test
    void reportsNothingWhenTheDaemonStayedReady() {
        assertThat(outcome(DagRun.NodeState.SUCCESS, DagDaemon.Health.READY))
                .isEqualTo(WorkflowRun.DaemonOutcome.NONE);
    }

    @Test
    void reportsAnUnavailableDaemonAsLost() {
        assertThat(outcome(DagRun.NodeState.SUCCESS, DagDaemon.Health.UNAVAILABLE))
                .isEqualTo(WorkflowRun.DaemonOutcome.LOST);
    }

    @Test
    void reportsAnExitedDaemonAsLost() {
        assertThat(outcome(DagRun.NodeState.SUCCESS, DagDaemon.Health.EXITED))
                .isEqualTo(WorkflowRun.DaemonOutcome.LOST);
    }

    @Test
    void reportsAStartupTimeoutAsNeverReady() {
        assertThat(outcome(DagRun.NodeState.TIMED_OUT, DagDaemon.Health.UNKNOWN))
                .isEqualTo(WorkflowRun.DaemonOutcome.NEVER_READY);
    }

    @Test
    void reportsAFailedStartupAsNeverReady() {
        assertThat(outcome(DagRun.NodeState.FAILED, DagDaemon.Health.STARTING))
                .isEqualTo(WorkflowRun.DaemonOutcome.NEVER_READY);
    }

    @Test
    void reportsAnInconclusiveCheckAfterReadinessAsLost() {
        assertThat(outcome(DagRun.NodeState.SUCCESS, DagDaemon.Health.UNKNOWN))
                .isEqualTo(WorkflowRun.DaemonOutcome.LOST);
    }

    @Test
    void ignoresADaemonThatWasNeverAttempted() {
        assertThat(outcome(DagRun.NodeState.SKIPPED, DagDaemon.Health.UNKNOWN))
                .isEqualTo(WorkflowRun.DaemonOutcome.NONE);
    }

    @Test
    void doesNotBlameADaemonCancelledBeforeItStarted() {
        assertThat(outcome(DagRun.NodeState.CANCELLED, DagDaemon.Health.UNKNOWN))
                .isEqualTo(WorkflowRun.DaemonOutcome.NONE);
    }
}
