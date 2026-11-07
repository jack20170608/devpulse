package io.github.devpulse.core.dag;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DagRun {
    public enum RunState { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }

    public enum NodeState {
        PENDING, RUNNING, RETRYING, SUCCESS, FAILED, TIMED_OUT, SKIPPED, CANCELLED;

        public boolean isTerminal() {
            return switch (this) {
                case SUCCESS, FAILED, TIMED_OUT, SKIPPED, CANCELLED -> true;
                default -> false;
            };
        }
    }

    public record NodeResult(NodeState state, int attempts, Object value,
            Throwable error, Instant startedAt, Instant finishedAt) {}

    public record Result(RunState state, Map<TaskKey<?>, NodeResult> tasks,
            boolean cancellationRequested, Throwable error,
            Instant startedAt, Instant finishedAt) {
        public Result {
            tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
        }

        public NodeResult task(TaskKey<?> key) {
            var result = tasks.get(Objects.requireNonNull(key, "key"));
            if (result == null) {
                throw new IllegalArgumentException("Unknown task or result type mismatch: " + key);
            }
            return result;
        }

        public <T> T result(TaskKey<T> key) {
            var result = task(key);
            if (result.state() != NodeState.SUCCESS) {
                throw new IllegalStateException("Task " + key.id() + " is " + result.state(),
                        result.error());
            }
            return key.resultType().cast(result.value());
        }
    }

    public record Event(Instant timestamp, RunState runState,
            TaskKey<?> task, NodeResult result) {}

    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event);
    }

    private sealed interface Message permits Update, Completed, Wake {}
    private record Update(TaskKey<?> key, NodeResult result) implements Message {}
    private record Completed(TaskKey<?> key, NodeResult result, Error fatal) implements Message {}
    private record Wake() implements Message {}

    private final DagWorkflow workflow;
    private final int parallelism;
    private final Listener listener;
    private final Object gate = new Object();
    private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
    private final Map<TaskKey<?>, NodeResult> results = new LinkedHashMap<>();
    private final Map<TaskKey<?>, Thread> workers = new LinkedHashMap<>();
    private volatile boolean cancellationRequested;
    private boolean executed;
    private RunState state = RunState.PENDING;
    private Throwable error;
    private Instant startedAt;
    private Instant finishedAt;

    DagRun(DagWorkflow workflow, int parallelism, Listener listener) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive.");
        }
        this.workflow = workflow;
        this.parallelism = parallelism;
        this.listener = Objects.requireNonNull(listener, "listener");
        workflow.nodes().values().forEach(node -> results.put(node.key(),
                new NodeResult(NodeState.PENDING, 0, null, null, null, null)));
    }

    public Result snapshot() {
        synchronized (gate) {
            return new Result(state, results, cancellationRequested, error, startedAt, finishedAt);
        }
    }

    public boolean cancel() {
        synchronized (gate) {
            if (finishedAt != null || cancellationRequested) {
                return false;
            }
            cancellationRequested = true;
            workers.values().forEach(Thread::interrupt);
            messages.add(new Wake());
            return true;
        }
    }

    public Result execute() throws InterruptedException {
        synchronized (gate) {
            if (executed) {
                throw new IllegalStateException("A DagRun can only be executed once.");
            }
            executed = true;
            state = RunState.RUNNING;
            startedAt = Instant.now();
        }
        try (var timers = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("devpulse-dag-timeout").factory());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                emit(null, null);
                var remaining = new LinkedHashMap<TaskKey<?>, Integer>();
                var dependents = new LinkedHashMap<TaskKey<?>, List<TaskKey<?>>>();
                var ready = new ArrayDeque<TaskKey<?>>();
                for (var node : workflow.nodes().values()) {
                    remaining.put(node.key(), node.needs().size());
                    if (node.needs().isEmpty()) {
                        ready.addLast(node.key());
                    }
                    for (var dependency : node.needs()) {
                        dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>())
                                .add(node.key());
                    }
                }
                var submitted = new HashSet<TaskKey<?>>();
                var active = 0;
                var pendingCancelled = false;
                while (true) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException("Workflow coordinator interrupted.");
                    }
                    while (active < parallelism && !ready.isEmpty()) {
                        synchronized (gate) {
                            if (cancellationRequested) {
                                break;
                            }
                            var key = ready.removeFirst();
                            var node = workflow.nodes().get(key.id());
                            var values = new LinkedHashMap<TaskKey<?>, Object>();
                            node.needs().forEach(dependency ->
                                    values.put(dependency, results.get(dependency).value()));
                            pool.submit(() -> runNode(node, values, timers));
                            submitted.add(key);
                            active++;
                        }
                    }
                    if (cancellationRequested && !pendingCancelled) {
                        for (var key : workflow.topologicalOrder()) {
                            if (!submitted.contains(key) && !current(key).state().isTerminal()) {
                                update(key, cancelled(current(key)));
                            }
                        }
                        pendingCancelled = true;
                    }
                    if (active == 0) {
                        break;
                    }
                    switch (messages.take()) {
                        case Wake ignored -> {}
                        case Update update -> update(update.key(), update.result());
                        case Completed completed -> {
                            active--;
                            update(completed.key(), completed.result());
                            if (completed.fatal() != null) {
                                throw completed.fatal();
                            }
                            if (!cancellationRequested) {
                                propagate(completed.key(), remaining, dependents, ready);
                            }
                        }
                    }
                }
                synchronized (gate) {
                    state = cancellationRequested ? RunState.CANCELLED
                            : results.values().stream().allMatch(
                                    result -> result.state() == NodeState.SUCCESS)
                            ? RunState.SUCCESS : RunState.FAILED;
                    finishedAt = Instant.now();
                }
                emit(null, null);
                return snapshot();
            } catch (InterruptedException exception) {
                abort(RunState.CANCELLED, exception);
                throw exception;
            } catch (RuntimeException | Error exception) {
                abort(RunState.FAILED, exception);
                throw exception;
            }
        }
    }

    private void propagate(TaskKey<?> completed, Map<TaskKey<?>, Integer> remaining,
            Map<TaskKey<?>, List<TaskKey<?>>> dependents, ArrayDeque<TaskKey<?>> ready) {
        var finished = new ArrayDeque<TaskKey<?>>();
        finished.add(completed);
        while (!finished.isEmpty() && !cancellationRequested) {
            for (var next : dependents.getOrDefault(finished.removeFirst(), List.of())) {
                if (remaining.compute(next, (key, value) -> value - 1) != 0) {
                    continue;
                }
                var unsuccessful = workflow.nodes().get(next.id()).needs().stream()
                        .filter(key -> current(key).state() != NodeState.SUCCESS).toList();
                if (unsuccessful.isEmpty()) {
                    ready.addLast(next);
                } else {
                    update(next, new NodeResult(NodeState.SKIPPED, 0, null,
                            new IllegalStateException("Prerequisites did not succeed: " + unsuccessful),
                            null, Instant.now()));
                    finished.addLast(next);
                }
            }
        }
    }

    private void runNode(DagWorkflow.Node<?> node, Map<TaskKey<?>, Object> dependencies,
            ScheduledExecutorService timers) {
        synchronized (gate) {
            if (cancellationRequested) {
                messages.add(new Completed(node.key(), cancelled(current(node.key())), null));
                return;
            }
            workers.put(node.key(), Thread.currentThread());
        }
        try {
            var outcome = attempts(node, dependencies, timers);
            messages.add(new Completed(node.key(), outcome,
                    outcome.error() instanceof Error fatal ? fatal : null));
        } catch (RuntimeException exception) {
            var previous = current(node.key());
            messages.add(new Completed(node.key(), new NodeResult(NodeState.FAILED,
                    previous.attempts(), null, exception, previous.startedAt(), Instant.now()), null));
        } catch (Error fatal) {
            var previous = current(node.key());
            messages.add(new Completed(node.key(), new NodeResult(NodeState.FAILED,
                    previous.attempts(), null, fatal, previous.startedAt(), Instant.now()), fatal));
        } finally {
            synchronized (gate) {
                workers.remove(node.key());
            }
        }
    }

    private NodeResult attempts(DagWorkflow.Node<?> node, Map<TaskKey<?>, Object> dependencies,
            ScheduledExecutorService timers) {
        var firstStarted = Instant.now();
        for (var attempt = 1; ; attempt++) {
            if (cancellationRequested) {
                return new NodeResult(NodeState.CANCELLED, attempt - 1, null,
                        new CancellationException("Workflow cancelled."), firstStarted, Instant.now());
            }
            messages.add(new Update(node.key(), new NodeResult(NodeState.RUNNING,
                    attempt, null, null, firstStarted, null)));
            var outcome = attempt(node, dependencies, timers, attempt, firstStarted);
            if (outcome.state() == NodeState.SUCCESS || outcome.state() == NodeState.CANCELLED
                    || outcome.error() instanceof InterruptedException
                    || outcome.error() instanceof Error
                    || attempt >= node.options().maxAttempts()) {
                return outcome;
            }
            if (cancellationRequested) {
                return cancelled(outcome);
            }
            messages.add(new Update(node.key(), new NodeResult(NodeState.RETRYING,
                    attempt, null, outcome.error(), firstStarted, null)));
            try {
                Thread.sleep(node.options().retryDelay());
            } catch (InterruptedException exception) {
                return new NodeResult(cancellationRequested ? NodeState.CANCELLED : NodeState.FAILED,
                        attempt, null, exception, firstStarted, Instant.now());
            }
        }
    }

    private NodeResult attempt(DagWorkflow.Node<?> node, Map<TaskKey<?>, Object> dependencies,
            ScheduledExecutorService timers, int attempt, Instant firstStarted) {
        var worker = Thread.currentThread();
        var timeout = node.options().timeout();
        var attemptGate = new Object();
        var finished = new boolean[1];
        var expired = new boolean[1];
        var began = System.nanoTime();
        var timer = timeout == null ? null : timers.schedule(() -> {
            synchronized (attemptGate) {
                if (!finished[0]) {
                    expired[0] = true;
                    worker.interrupt();
                }
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        Object value = null;
        Throwable failure = null;
        boolean timedOut;
        try {
            var context = new TaskContext(node.key().id(), attempt, dependencies,
                    () -> cancellationRequested);
            context.checkCancelled();
            value = node.key().resultType().cast(node.task().execute(context));
        } catch (Exception | Error exception) {
            failure = exception;
        } finally {
            synchronized (attemptGate) {
                finished[0] = true;
                timedOut = expired[0] || (timeout != null
                        && System.nanoTime() - began >= timeout.toNanos());
            }
            if (timer != null) {
                timer.cancel(false);
            }
        }
        if (failure instanceof Error) {
            return new NodeResult(NodeState.FAILED, attempt, null,
                    failure, firstStarted, Instant.now());
        }
        if (cancellationRequested) {
            return new NodeResult(NodeState.CANCELLED, attempt, null,
                    new CancellationException("Workflow cancelled."), firstStarted, Instant.now());
        }
        if (timedOut) {
            // The previous invocation has exited before its timeout interrupt is consumed.
            Thread.interrupted();
            var exception = new TimeoutException(
                    "Task " + node.key().id() + " attempt " + attempt + " exceeded " + timeout);
            if (failure != null) {
                exception.initCause(failure);
            }
            return new NodeResult(NodeState.TIMED_OUT, attempt, null,
                    exception, firstStarted, Instant.now());
        }
        if (failure == null && worker.isInterrupted()) {
            failure = new InterruptedException("Task interrupted.");
        }
        return new NodeResult(failure == null ? NodeState.SUCCESS : NodeState.FAILED,
                attempt, failure == null ? value : null, failure, firstStarted, Instant.now());
    }

    private NodeResult current(TaskKey<?> key) {
        synchronized (gate) {
            return results.get(key);
        }
    }

    private void update(TaskKey<?> key, NodeResult result) {
        synchronized (gate) {
            results.put(key, result);
        }
        emit(key, result);
    }

    private void emit(TaskKey<?> key, NodeResult result) {
        listener.onEvent(new Event(Instant.now(), snapshotState(), key, result));
    }

    private RunState snapshotState() {
        synchronized (gate) {
            return state;
        }
    }

    private static NodeResult cancelled(NodeResult previous) {
        return new NodeResult(NodeState.CANCELLED, previous.attempts(), null,
                new CancellationException("Workflow cancelled."),
                previous.startedAt(), Instant.now());
    }

    private void abort(RunState terminalState, Throwable cause) {
        synchronized (gate) {
            cancellationRequested = true;
            workers.values().forEach(Thread::interrupt);
            results.replaceAll((key, result) ->
                    result.state().isTerminal() ? result : cancelled(result));
            state = terminalState;
            error = cause;
            finishedAt = Instant.now();
        }
    }
}
