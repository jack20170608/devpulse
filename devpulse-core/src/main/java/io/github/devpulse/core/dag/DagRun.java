package io.github.devpulse.core.dag;

import java.time.Duration;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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

    public record DaemonResult(DagDaemon.Health health, Throwable error, Instant checkedAt) {}

    public record Result(RunState state, Map<TaskKey<?>, NodeResult> tasks,
            Map<TaskKey<?>, DaemonResult> daemons, boolean cancellationRequested,
            Throwable error, Instant startedAt, Instant finishedAt) {
        public Result {
            tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
            daemons = Collections.unmodifiableMap(new LinkedHashMap<>(daemons));
        }

        public NodeResult task(TaskKey<?> key) {
            var result = tasks.get(Objects.requireNonNull(key, "key"));
            if (result == null) {
                throw new IllegalArgumentException("Unknown task or result type mismatch: " + key);
            }
            return result;
        }

        public DaemonResult daemon(TaskKey<?> key) {
            var result = daemons.get(Objects.requireNonNull(key, "key"));
            if (result == null) {
                throw new IllegalArgumentException("Not a daemon or result type mismatch: " + key);
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
            TaskKey<?> task, NodeResult result, DaemonResult daemon) {}

    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event);
    }

    private sealed interface Message permits Update, Completed, HealthChanged, Wake {}
    private record Update(TaskKey<?> key, NodeResult result) implements Message {}
    private record Completed(TaskKey<?> key, Outcome outcome) implements Message {}
    private record HealthChanged(TaskKey<?> key, DaemonResult result) implements Message {}
    private record Wake() implements Message {}

    private record Started(Object value, DagDaemon.Handle<?> daemon) {}
    private record Outcome(NodeResult result, DagDaemon.Handle<?> daemon) {}
    private record Invocation<T>(T value, Throwable error, boolean timedOut) {}

    private final DagWorkflow workflow;
    private final int parallelism;
    private final Listener listener;
    private final Object gate = new Object();
    private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
    private final Map<TaskKey<?>, NodeResult> results = new LinkedHashMap<>();
    private final Map<TaskKey<?>, DaemonResult> daemonResults = new LinkedHashMap<>();
    private final Map<TaskKey<?>, DagDaemon.Handle<?>> handles = new LinkedHashMap<>();
    private final Map<TaskKey<?>, Thread> workers = new LinkedHashMap<>();
    private final Map<TaskKey<?>, Thread> monitors = new LinkedHashMap<>();
    private final Map<TaskKey<?>, Throwable> nodeCancellations = new LinkedHashMap<>();
    private volatile boolean cancellationRequested;
    private volatile Throwable failureRequested;
    private volatile boolean monitoring = true;
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
        workflow.nodes().values().forEach(node -> {
            results.put(node.key(), new NodeResult(NodeState.PENDING, 0, null, null, null, null));
            if (node.action() instanceof DagWorkflow.Daemon<?>) {
                daemonResults.put(node.key(), new DaemonResult(DagDaemon.Health.UNKNOWN, null, null));
            }
        });
    }

    public Result snapshot() {
        synchronized (gate) {
            return new Result(state, results, daemonResults, cancellationRequested,
                    error, startedAt, finishedAt);
        }
    }

    public boolean cancel() {
        synchronized (gate) {
            if (finishedAt != null || cancellationRequested) {
                return false;
            }
            cancellationRequested = true;
            workers.values().forEach(Thread::interrupt);
            monitors.values().forEach(Thread::interrupt);
            messages.add(new Wake());
            return true;
        }
    }

    public boolean fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (gate) {
            if (finishedAt != null || failureRequested != null) {
                return false;
            }
            failureRequested = cause;
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
                emit(null, null, null);
                var remaining = new LinkedHashMap<TaskKey<?>, Integer>();
                var dependents = new LinkedHashMap<TaskKey<?>, List<TaskKey<?>>>();
                var ready = new ArrayDeque<TaskKey<?>>();
                for (var node : workflow.nodes().values()) {
                    var needs = workflow.graph().node(node.key()).needs();
                    remaining.put(node.key(), needs.size());
                    if (needs.isEmpty()) {
                        ready.addLast(node.key());
                    }
                    for (var dependency : needs) {
                        dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>())
                                .add(node.key());
                    }
                }
                var submitted = new HashSet<TaskKey<?>>();
                var active = 0;
                while (true) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException("Workflow coordinator interrupted.");
                    }
                    if (failureRequested != null) {
                        throw infrastructureFailure(failureRequested);
                    }
                    Message message;
                    while ((message = messages.poll()) != null) {
                        active += process(message, remaining, dependents, ready, submitted, timers);
                    }
                    while (active < parallelism && !ready.isEmpty()) {
                        synchronized (gate) {
                            if (cancellationRequested || failureRequested != null) {
                                break;
                            }
                            var key = ready.removeFirst();
                            if (nodeCancellations.containsKey(key)) {
                                continue;
                            }
                            var node = workflow.nodes().get(key.id());
                            var values = new LinkedHashMap<TaskKey<?>, Object>();
                            workflow.graph().node(node.key()).needs().forEach(dependency ->
                                    values.put(dependency, results.get(dependency).value()));
                            pool.submit(() -> runNode(node, values, timers));
                            submitted.add(key);
                            active++;
                        }
                    }
                    if (cancellationRequested) {
                        for (var key : workflow.topologicalOrder()) {
                            if (!submitted.contains(key) && !current(key).state().isTerminal()) {
                                update(key, cancelled(current(key), cancellationCause(key)));
                            }
                        }
                    }
                    if (active == 0) {
                        break;
                    }
                    active += process(messages.take(), remaining, dependents, ready, submitted, timers);
                }
                stopMonitors();
                if (Thread.interrupted()) {
                    throw new InterruptedException("Workflow coordinator interrupted.");
                }
                Message message;
                while ((message = messages.poll()) != null) {
                    process(message, remaining, dependents, ready, submitted, timers);
                }
                if (!cancellationRequested) {
                    for (var entry : handles.entrySet()) {
                        if (isNodeCancelled(entry.getKey())
                                || daemonResults.get(entry.getKey()).health() != DagDaemon.Health.READY) {
                            continue;
                        }
                        var key = entry.getKey();
                        var options = ((DagWorkflow.Daemon<?>) workflow.nodes().get(key.id())
                                .action()).options();
                        try {
                            var health = pool.submit(() -> check(entry.getValue(), options, timers)).get();
                            process(new HealthChanged(key, health), remaining, dependents,
                                    ready, submitted, timers);
                        } catch (ExecutionException exception) {
                            if (exception.getCause() instanceof Error fatal) {
                                throw fatal;
                            }
                            throw new IllegalStateException(exception.getCause());
                        }
                    }
                }
                synchronized (gate) {
                    if (failureRequested != null) {
                        throw infrastructureFailure(failureRequested);
                    }
                    state = cancellationRequested ? RunState.CANCELLED
                            : results.values().stream().allMatch(
                                    result -> result.state() == NodeState.SUCCESS)
                                    && daemonResults.values().stream().allMatch(
                                            result -> result.health() == DagDaemon.Health.READY)
                            ? RunState.SUCCESS : RunState.FAILED;
                    finishedAt = Instant.now();
                }
                emit(null, null, null);
                return snapshot();
            } catch (InterruptedException exception) {
                abort(RunState.CANCELLED, exception);
                throw exception;
            } catch (RuntimeException | Error exception) {
                abort(RunState.FAILED, failureRequested == null ? exception : failureRequested);
                throw exception;
            } finally {
                stopMonitors();
            }
        }
    }

    private int process(Message message, Map<TaskKey<?>, Integer> remaining,
            Map<TaskKey<?>, List<TaskKey<?>>> dependents, ArrayDeque<TaskKey<?>> ready,
            HashSet<TaskKey<?>> submitted, ScheduledExecutorService timers) {
        return switch (message) {
            case Wake ignored -> 0;
            case Update update -> {
                update(update.key(), update.result());
                yield 0;
            }
            case Completed completed -> {
                var key = completed.key();
                var outcome = completed.outcome();
                var result = outcome.result();
                if (result.error() instanceof Error fatal) {
                    update(key, result);
                    throw fatal;
                }
                if (isNodeCancelled(key)) {
                    result = cancelled(result, cancellationCause(key));
                }
                update(key, result);
                if (result.state() == NodeState.SUCCESS && outcome.daemon() != null) {
                    handles.put(key, outcome.daemon());
                    updateHealth(key, new DaemonResult(DagDaemon.Health.READY, null, Instant.now()));
                    if (!cancellationRequested && monitoring) {
                        startMonitor(key, outcome.daemon(), timers);
                    }
                } else if (daemonResults.containsKey(key)) {
                    updateHealth(key, new DaemonResult(DagDaemon.Health.UNKNOWN,
                            result.error(), Instant.now()));
                }
                if (!cancellationRequested) {
                    propagate(key, remaining, dependents, ready);
                }
                yield -1;
            }
            case HealthChanged changed -> {
                if (changed.result().error() instanceof Error fatal) {
                    throw fatal;
                }
                if (!isNodeCancelled(changed.key())) {
                    updateHealth(changed.key(), changed.result());
                    if (changed.result().health() != DagDaemon.Health.READY) {
                        invalidate(changed.key(), changed.result(), dependents, ready, submitted);
                    }
                }
                yield 0;
            }
        };
    }

    private void propagate(TaskKey<?> completed, Map<TaskKey<?>, Integer> remaining,
            Map<TaskKey<?>, List<TaskKey<?>>> dependents, ArrayDeque<TaskKey<?>> ready) {
        var finished = new ArrayDeque<TaskKey<?>>();
        finished.add(completed);
        while (!finished.isEmpty() && !cancellationRequested) {
            for (var next : dependents.getOrDefault(finished.removeFirst(), List.of())) {
                if (isNodeCancelled(next)
                        || remaining.compute(next, (key, value) -> value - 1) != 0) {
                    continue;
                }
                var unsuccessful = workflow.graph().node(next).needs().stream()
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

    private void invalidate(TaskKey<?> daemon, DaemonResult health,
            Map<TaskKey<?>, List<TaskKey<?>>> dependents, ArrayDeque<TaskKey<?>> ready,
            HashSet<TaskKey<?>> submitted) {
        var cause = new IllegalStateException("Daemon " + daemon.id()
                + " became " + health.health(), health.error());
        var pending = new ArrayDeque<>(dependents.getOrDefault(daemon, List.of()));
        var visited = new HashSet<TaskKey<?>>();
        while (!pending.isEmpty()) {
            var key = pending.removeFirst();
            if (!visited.add(key)) {
                continue;
            }
            pending.addAll(dependents.getOrDefault(key, List.of()));
            synchronized (gate) {
                nodeCancellations.putIfAbsent(key, cause);
                var worker = workers.get(key);
                if (worker != null) {
                    worker.interrupt();
                }
                var monitor = monitors.get(key);
                if (monitor != null) {
                    monitor.interrupt();
                }
            }
            ready.remove(key);
            var previous = current(key);
            if (!submitted.contains(key) && !previous.state().isTerminal()) {
                update(key, new NodeResult(NodeState.SKIPPED, 0, null,
                        cause, null, Instant.now()));
            }
            if (handles.containsKey(key)) {
                updateHealth(key, new DaemonResult(DagDaemon.Health.UNAVAILABLE, cause, Instant.now()));
            }
        }
    }

    private void runNode(DagWorkflow.Node<?> node, Map<TaskKey<?>, Object> dependencies,
            ScheduledExecutorService timers) {
        synchronized (gate) {
            if (isNodeCancelled(node.key())) {
                messages.add(new Completed(node.key(), new Outcome(
                        cancelled(current(node.key()), cancellationCause(node.key())), null)));
                return;
            }
            workers.put(node.key(), Thread.currentThread());
        }
        try {
            messages.add(new Completed(node.key(), attempts(node, dependencies, timers)));
        } catch (RuntimeException | Error exception) {
            var previous = current(node.key());
            messages.add(new Completed(node.key(), new Outcome(new NodeResult(NodeState.FAILED,
                    previous.attempts(), null, exception, previous.startedAt(), Instant.now()), null)));
        } finally {
            synchronized (gate) {
                workers.remove(node.key());
            }
        }
    }

    private Outcome attempts(DagWorkflow.Node<?> node, Map<TaskKey<?>, Object> dependencies,
            ScheduledExecutorService timers) {
        var firstStarted = Instant.now();
        for (var attempt = 1; ; attempt++) {
            if (isNodeCancelled(node.key())) {
                return new Outcome(new NodeResult(NodeState.CANCELLED, attempt - 1, null,
                        cancellationCause(node.key()), firstStarted, Instant.now()), null);
            }
            messages.add(new Update(node.key(), new NodeResult(NodeState.RUNNING,
                    attempt, null, null, firstStarted, null)));
            var outcome = attempt(node, dependencies, timers, attempt, firstStarted);
            var result = outcome.result();
            if (result.state() == NodeState.SUCCESS || result.state() == NodeState.CANCELLED
                    || result.error() instanceof InterruptedException
                    || result.error() instanceof Error || attempt >= node.options().maxAttempts()) {
                return outcome;
            }
            if (isNodeCancelled(node.key())) {
                return new Outcome(cancelled(result, cancellationCause(node.key())), null);
            }
            messages.add(new Update(node.key(), new NodeResult(NodeState.RETRYING,
                    attempt, null, result.error(), firstStarted, null)));
            try {
                Thread.sleep(node.options().retryDelay());
            } catch (InterruptedException exception) {
                return new Outcome(new NodeResult(isNodeCancelled(node.key())
                        ? NodeState.CANCELLED : NodeState.FAILED, attempt, null,
                        exception, firstStarted, Instant.now()), null);
            }
        }
    }

    private Outcome attempt(DagWorkflow.Node<?> node, Map<TaskKey<?>, Object> dependencies,
            ScheduledExecutorService timers, int attempt, Instant firstStarted) {
        var timeout = node.options().timeout();
        if (node.action() instanceof DagWorkflow.Daemon<?> daemon
                && (timeout == null || daemon.options().readyTimeout().compareTo(timeout) < 0)) {
            timeout = daemon.options().readyTimeout();
        }
        var invocation = invoke(() -> {
            var context = new TaskContext(node.key().id(), attempt, dependencies,
                    () -> isNodeCancelled(node.key()), this::fail,
                    node.action() instanceof DagWorkflow.Daemon<?> daemon ? daemon.options() : null);
            context.checkCancelled();
            return switch (node.action()) {
                case DagWorkflow.Batch<?> batch ->
                    new Started(node.key().resultType().cast(batch.task().execute(context)), null);
                case DagWorkflow.Daemon<?> daemon -> {
                    var handle = Objects.requireNonNull(daemon.task().start(context), "daemon handle");
                    while (true) {
                        context.checkCancelled();
                        var health = check(handle, daemon.options(), timers);
                        context.checkCancelled();
                        if (health.health() == DagDaemon.Health.READY) {
                            yield new Started(node.key().resultType().cast(handle.value()), handle);
                        }
                        if (health.health() == DagDaemon.Health.EXITED
                                || health.health() == DagDaemon.Health.UNKNOWN) {
                            throw new IllegalStateException("Daemon did not become ready: "
                                    + health.health(), health.error());
                        }
                        Thread.sleep(daemon.options().checkInterval());
                    }
                }
            };
        }, timeout, timers);
        var failure = invocation.error();
        NodeState resultState;
        if (failure instanceof Error) {
            resultState = NodeState.FAILED;
        } else if (isNodeCancelled(node.key())) {
            resultState = NodeState.CANCELLED;
            failure = cancellationCause(node.key());
        } else if (invocation.timedOut()) {
            resultState = NodeState.TIMED_OUT;
            failure = timeoutFailure("Task " + node.key().id() + " attempt " + attempt,
                    timeout, failure);
        } else {
            if (failure == null && Thread.currentThread().isInterrupted()) {
                failure = new InterruptedException("Task interrupted.");
            }
            resultState = failure == null ? NodeState.SUCCESS : NodeState.FAILED;
        }
        var started = invocation.value();
        return new Outcome(new NodeResult(resultState, attempt,
                resultState == NodeState.SUCCESS ? started.value() : null,
                failure, firstStarted, Instant.now()),
                resultState == NodeState.SUCCESS ? started.daemon() : null);
    }

    private DaemonResult check(DagDaemon.Handle<?> handle, DaemonOptions options,
            ScheduledExecutorService timers) {
        var invocation = invoke(() -> Objects.requireNonNull(handle.check(), "daemon health"),
                options.checkTimeout(), timers);
        if (invocation.error() instanceof Error fatal) {
            throw fatal;
        }
        var failure = invocation.timedOut()
                ? timeoutFailure("Daemon health check", options.checkTimeout(), invocation.error())
                : invocation.error();
        return new DaemonResult(failure == null ? invocation.value() : DagDaemon.Health.UNKNOWN,
                failure, Instant.now());
    }

    private static <T> Invocation<T> invoke(Callable<T> action, Duration timeout,
            ScheduledExecutorService timers) {
        var worker = Thread.currentThread();
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
        T value = null;
        Throwable failure = null;
        boolean timedOut;
        try {
            value = action.call();
        } catch (Exception | Error exception) {
            failure = exception;
        } finally {
            synchronized (attemptGate) {
                finished[0] = true;
                timedOut = expired[0] || timeout != null
                        && System.nanoTime() - began >= timeout.toNanos();
            }
            if (timer != null) {
                timer.cancel(false);
            }
        }
        if (timedOut) {
            Thread.interrupted();
        }
        return new Invocation<>(value, failure, timedOut);
    }

    private void startMonitor(TaskKey<?> key, DagDaemon.Handle<?> handle,
            ScheduledExecutorService timers) {
        var options = ((DagWorkflow.Daemon<?>) workflow.nodes().get(key.id()).action()).options();
        var thread = Thread.ofVirtual().name("devpulse-dag-monitor-" + key.id()).unstarted(() -> {
            try {
                while (monitoring && !isNodeCancelled(key)) {
                    var health = check(handle, options, timers);
                    if (!monitoring || isNodeCancelled(key)) {
                        return;
                    }
                    if (health.health() != DagDaemon.Health.READY) {
                        messages.add(new HealthChanged(key, health));
                        return;
                    }
                    messages.add(new HealthChanged(key, health));
                    Thread.sleep(options.checkInterval());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Error fatal) {
                messages.add(new HealthChanged(key, new DaemonResult(
                        DagDaemon.Health.UNKNOWN, fatal, Instant.now())));
            }
        });
        synchronized (gate) {
            monitors.put(key, thread);
            thread.start();
        }
    }

    private void stopMonitors() {
        List<Thread> threads;
        synchronized (gate) {
            monitoring = false;
            threads = List.copyOf(monitors.values());
            threads.forEach(Thread::interrupt);
        }
        var interrupted = false;
        for (var thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isNodeCancelled(TaskKey<?> key) {
        synchronized (gate) {
            return cancellationRequested || failureRequested != null
                    || nodeCancellations.containsKey(key);
        }
    }

    private Throwable cancellationCause(TaskKey<?> key) {
        synchronized (gate) {
            var cause = nodeCancellations.get(key);
            return cause == null ? new CancellationException("Workflow cancelled.") : cause;
        }
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
        emit(key, result, null);
    }

    private void updateHealth(TaskKey<?> key, DaemonResult result) {
        DaemonResult previous;
        synchronized (gate) {
            previous = daemonResults.put(key, result);
        }
        if (previous == null || previous.health() != result.health()
                || previous.error() != result.error()) {
            emit(key, current(key), result);
        }
    }

    private void emit(TaskKey<?> key, NodeResult result, DaemonResult daemon) {
        RunState currentState;
        synchronized (gate) {
            currentState = state;
        }
        listener.onEvent(new Event(Instant.now(), currentState, key, result, daemon));
    }

    private static NodeResult cancelled(NodeResult previous, Throwable cause) {
        return new NodeResult(NodeState.CANCELLED, previous.attempts(), null,
                cause, previous.startedAt(), Instant.now());
    }

    private static TimeoutException timeoutFailure(String name, Duration timeout, Throwable cause) {
        var exception = new TimeoutException(name + " exceeded " + timeout);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private static RuntimeException infrastructureFailure(Throwable cause) {
        if (cause instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException("Workflow infrastructure failed.", cause);
    }

    private void abort(RunState terminalState, Throwable cause) {
        synchronized (gate) {
            cancellationRequested = true;
            workers.values().forEach(Thread::interrupt);
            results.replaceAll((key, result) -> result.state().isTerminal()
                    ? result : cancelled(result, cause));
            state = terminalState;
            error = cause;
            finishedAt = Instant.now();
        }
    }
}
