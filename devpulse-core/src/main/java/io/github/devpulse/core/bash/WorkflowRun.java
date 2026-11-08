package io.github.devpulse.core.bash;

import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagRun;
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public final class WorkflowRun {
    public record Result(RunHistory.State state, int succeeded, int failed, int skipped) {}

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
            return new Result(state, succeeded, failed, skipped);
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
                history.daemonChecked(event.task().id(), event.daemon().health(),
                        message(event.daemon().error()), event.daemon().checkedAt());
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
        var state = switch (result.state()) {
            case SUCCESS -> workflow.isDaemon(key)
                    ? RunHistory.State.READY : RunHistory.State.SUCCESS;
            case FAILED, TIMED_OUT -> RunHistory.State.FAILED;
            case SKIPPED -> RunHistory.State.SKIPPED;
            case CANCELLED -> RunHistory.State.CANCELLED;
            default -> RunHistory.State.INTERRUPTED;
        };
        Integer code = null;
        if (result.error() instanceof BashTaskAdapter.ExitFailure failure) {
            code = failure.exitCode();
        }
        history.completed(key.id(), state, code, message(result.error()),
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
                history.daemonChecked(entry.getKey().id(), health.health(),
                        message(health.error()), health.checkedAt());
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
