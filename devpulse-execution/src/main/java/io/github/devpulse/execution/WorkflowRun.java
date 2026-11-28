package io.github.devpulse.execution;

import io.github.devpulse.bash.BashTaskAdapter;
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagRun;
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;

public final class WorkflowRun {
    /** How the run's daemons behaved, so a caller can classify why the run failed. */
    public enum DaemonOutcome {
        /** No daemon failed, or the run had no daemons. */
        NONE,
        /** A daemon never reached readiness, which points at startup rather than at a task. */
        NEVER_READY,
        /** A daemon had started and later became unavailable or exited. */
        LOST
    }

    public record Result(
            RunHistory.State state,
            int succeeded,
            int failed,
            int skipped,
            DaemonOutcome daemons) {}

    /**
     * Classify daemon health for exit-code purposes.
     *
     * <p>The health value alone is not enough: {@code UNKNOWN} is both the initial value and the
     * value of an inconclusive check, so the startup node's outcome decides which. A successful
     * startup node means readiness had been reached; a skipped or cancelled node means the daemon
     * was never attempted and cannot be blamed.
     */
    static DaemonOutcome outcome(
            Map<TaskKey<?>, DagRun.NodeResult> tasks,
            Map<TaskKey<?>, DagRun.DaemonResult> daemons) {
        var outcome = DaemonOutcome.NONE;
        for (var entry : daemons.entrySet()) {
            var node = tasks.get(entry.getKey());
            if (node == null) {
                continue;
            }
            var classified = classify(node.state(), entry.getValue().health());
            if (classified == DaemonOutcome.NEVER_READY) {
                return DaemonOutcome.NEVER_READY;
            }
            if (classified == DaemonOutcome.LOST) {
                outcome = DaemonOutcome.LOST;
            }
        }
        return outcome;
    }

    private static DaemonOutcome classify(DagRun.NodeState state, DagDaemon.Health health) {
        return switch (health) {
            case READY -> DaemonOutcome.NONE;
            case STARTING -> DaemonOutcome.NEVER_READY;
            case UNAVAILABLE, EXITED -> DaemonOutcome.LOST;
            case UNKNOWN ->
                    switch (state) {
                        case SUCCESS -> DaemonOutcome.LOST;
                        case FAILED, TIMED_OUT -> DaemonOutcome.NEVER_READY;
                        case PENDING, RUNNING, RETRYING, SKIPPED, CANCELLED -> DaemonOutcome.NONE;
                    };
        };
    }

    private final RunHistory.Writer history;
    private final DagWorkflow workflow;
    private final DagRun run;

    public WorkflowRun(DagWorkflow workflow, RunHistory.Writer history, int jobs) {
        this.history = Objects.requireNonNull(history, "history");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        run = workflow.newRun(jobs, this::onEvent);
    }

    public Result execute() throws IOException, InterruptedException {
        try {
            var result = run.execute();
            var state = RunHistory.State.valueOf(result.state().name());
            history.ended(state);
            var succeeded = 0;
            var failed = 0;
            for (var entry : result.tasks().entrySet()) {
                var node = entry.getValue();
                var health = result.daemons().get(entry.getKey());
                if (node.state() == DagRun.NodeState.SUCCESS
                        && (health == null || health.health() == DagDaemon.Health.READY)) {
                    succeeded++;
                } else if (node.state() == DagRun.NodeState.FAILED
                        || node.state() == DagRun.NodeState.TIMED_OUT
                        || node.state() == DagRun.NodeState.SUCCESS && health != null) {
                    failed++;
                }
            }
            var skipped = result.tasks().size() - succeeded - failed;
            return new Result(
                    state, succeeded, failed, skipped, outcome(result.tasks(), result.daemons()));
        } catch (InterruptedException exception) {
            finishFailure(RunHistory.State.CANCELLED, exception);
            throw exception;
        } catch (RuntimeException | Error exception) {
            finishFailure(RunHistory.State.FAILED, exception);
            var error = run.snapshot().error();
            if (error instanceof IOException io) {
                for (var suppressed : exception.getSuppressed()) {
                    if (suppressed != io) {
                        io.addSuppressed(suppressed);
                    }
                }
                throw io;
            }
            if (exception instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw exception;
        }
    }

    public void cancel() {
        run.cancel();
    }

    private void onEvent(DagRun.Event event) {
        if (event.task() == null) {
            return;
        }
        try {
            if (event.daemon() != null) {
                history.daemonChecked(
                        event.task().id(),
                        event.daemon().health(),
                        message(event.daemon().error()),
                        event.daemon().checkedAt());
            } else if (event.result().state() == DagRun.NodeState.RUNNING) {
                history.started(event.task().id());
            } else if (event.result().state().isTerminal()) {
                persist(event.task(), event.result());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void persist(TaskKey<?> key, DagRun.NodeResult result) throws IOException {
        var state =
                switch (result.state()) {
                    case SUCCESS ->
                            workflow.isDaemon(key)
                                    ? RunHistory.State.READY
                                    : RunHistory.State.SUCCESS;
                    case FAILED, TIMED_OUT -> RunHistory.State.FAILED;
                    case SKIPPED -> RunHistory.State.SKIPPED;
                    case CANCELLED -> RunHistory.State.CANCELLED;
                    default -> RunHistory.State.INTERRUPTED;
                };
        Integer code = null;
        if (result.error() instanceof BashTaskAdapter.ExitFailure failure) {
            code = failure.exitCode();
        }
        history.completed(
                key.id(),
                state,
                code,
                message(result.error()),
                state == RunHistory.State.READY ? null : result.finishedAt());
    }

    private void finishFailure(RunHistory.State state, Throwable failure) {
        var snapshot = run.snapshot();
        try {
            for (var entry : snapshot.tasks().entrySet()) {
                persist(entry.getKey(), entry.getValue());
            }
            for (var entry : snapshot.daemons().entrySet()) {
                var health = entry.getValue();
                history.daemonChecked(
                        entry.getKey().id(),
                        health.health(),
                        message(health.error()),
                        health.checkedAt());
            }
            history.ended(state);
        } catch (IOException exception) {
            failure.addSuppressed(exception);
        }
    }

    private static String message(Throwable error) {
        return error == null ? null : error.toString();
    }
}
