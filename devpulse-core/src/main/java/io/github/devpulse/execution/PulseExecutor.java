package io.github.devpulse.execution;

import io.github.devpulse.environment.BashShell;
import io.github.devpulse.execution.PulseConfiguration.Task;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PulseExecutor {
    private final BashShell bash;
    private final Path directory;
    private final PrintWriter out;
    private final RunHistory.Writer history;
    private final DaemonManager daemons;
    private final Object gate = new Object();
    private final Object cleanupGate = new Object();
    private final Set<ProcessHandle> processes = ConcurrentHashMap.newKeySet();
    private volatile boolean failed;
    private TaskPlan plan;

    private enum State { SUCCESS, READY, FAILED, SKIPPED }

    private record Result(String name, State state, Integer exitCode,
            String error, Instant finishedAt) {}

    public PulseExecutor(BashShell bash, Path directory, PrintWriter out,
            RunHistory.Writer history) {
        this.bash = bash;
        this.directory = directory;
        this.out = out;
        this.history = history;
        daemons = new DaemonManager(history, bash, directory);
    }

    public int execute(TaskPlan plan, int jobs)
            throws IOException, InterruptedException {
        this.plan = plan;
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        var completions = new ExecutorCompletionService<Result>(pool);
        var started = new HashSet<String>();
        var succeeded = new HashSet<String>();
        var reportedDaemons = new HashSet<String>();
        var active = 0;
        var failures = 0;
        IOException persistenceFailure = null;
        var hook = Thread.ofPlatform().unstarted(this::cancel);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            while (true) {
                failures += recordDaemonFailures(reportedDaemons, succeeded);
                synchronized (gate) {
                    if (!failed) {
                        for (var task : plan.tasks()) {
                            if (active >= jobs) {
                                break;
                            }
                            if (!started.contains(task.name())
                                    && succeeded.containsAll(task.needs())) {
                                completions.submit(() -> run(task, pool));
                                started.add(task.name());
                                active++;
                            }
                        }
                    }
                }
                if (active == 0) {
                    break;
                }
                Result result;
                try {
                    var completion = completions.poll(200,
                            TimeUnit.MILLISECONDS);
                    if (completion == null) {
                        continue;
                    }
                    result = completion.get();
                } catch (ExecutionException exception) {
                    cancel();
                    throw new IllegalStateException(
                            "Task execution failed unexpectedly.",
                            exception.getCause());
                }
                active--;
                switch (result.state()) {
                    case SUCCESS -> succeeded.add(result.name());
                    case READY -> {
                        if (!reportedDaemons.contains(result.name())) {
                            succeeded.add(result.name());
                        }
                    }
                    case FAILED -> failures++;
                    case SKIPPED -> { }
                }
                try {
                    if (!reportedDaemons.contains(result.name())) {
                        history.completed(result.name(),
                                RunHistory.State.valueOf(
                                        result.state().name()),
                                result.exitCode(), result.error(),
                                result.finishedAt());
                    }
                } catch (IOException exception) {
                    markFailed();
                    persistenceFailure = exception;
                    log("pulse", "Could not persist task state: "
                            + exception.getMessage());
                }
            }
            daemons.finishMonitoring();
            failures += recordDaemonFailures(reportedDaemons, succeeded);
            cleanupHooks();
            var skipped = plan.tasks().size() - succeeded.size() - failures;
            history.ended(failed || skipped > 0
                    ? RunHistory.State.FAILED : RunHistory.State.SUCCESS);
            log("pulse", "Finished: " + succeeded.size() + " succeeded, "
                    + failures + " failed, " + skipped + " skipped.");
            if (persistenceFailure != null) {
                throw new IOException("Run state could not be persisted.",
                        persistenceFailure);
            }
            return failed || skipped > 0 ? 1 : 0;
        } catch (InterruptedException exception) {
            cancel();
            throw exception;
        } finally {
            pool.shutdownNow();
            var interrupted = Thread.interrupted();
            try {
                cleanupHooks();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().removeShutdownHook(hook);
            }
        }
    }

    private Result run(Task task, ExecutorService pool) {
        if (task.daemon() != null) {
            return runDaemon(task, pool);
        }
        Process process = null;
        BufferedWriter taskLog = null;
        Integer exitCode = null;
        Instant finishedAt = null;
        try {
            synchronized (gate) {
                if (failed) {
                    return new Result(task.name(), State.SKIPPED,
                            null, null, Instant.now());
                }
                try {
                    history.started(task.name());
                    taskLog = history.openLog(task.name());
                    process = bash.script(directory).redirectErrorStream(true)
                            .start();
                } catch (IOException exception) {
                    failed = true;
                    throw exception;
                }
                processes.add(process.toHandle());
                log(task.name(), "Started.");
            }
            var running = process;
            var logFile = taskLog;
            var output = pool.submit(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8));
                        logFile) {
                    IOException logFailure = null;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log(task.name(), line);
                        if (logFailure == null) {
                            try {
                                logFile.write(line);
                                logFile.newLine();
                                logFile.flush();
                            } catch (IOException exception) {
                                markFailed();
                                logFailure = exception;
                                log(task.name(), "Could not persist log: "
                                        + exception.getMessage());
                            }
                        }
                    }
                    if (logFailure != null) {
                        throw logFailure;
                    }
                }
                return null;
            });
            var input = pool.submit(() -> {
                try (var stream = running.getOutputStream()) {
                    stream.write(task.command()
                            .getBytes(StandardCharsets.UTF_8));
                    stream.write('\n');
                }
                return null;
            });
            var code = process.waitFor();
            exitCode = code;
            finishedAt = Instant.now();
            if (code != 0) {
                markFailed();
            }
            output.get();
            if (code == 0) {
                try {
                    input.get();
                } catch (ExecutionException exception) {
                    // A successful early exit may close stdin before the
                    // complete script has been written.
                    if (!(exception.getCause() instanceof IOException)) {
                        throw exception;
                    }
                }
            }
            log(task.name(), "Finished with exit code " + code + ".");
            return new Result(task.name(),
                    code == 0 ? State.SUCCESS : State.FAILED, code,
                    code == 0 ? null : "Bash exited with code " + code + ".",
                    finishedAt);
        } catch (IOException | ExecutionException exception) {
            markFailed();
            log(task.name(), "Failed: " + exception.getMessage());
            return new Result(task.name(), State.FAILED, exitCode,
                    exception.getMessage(),
                    finishedAt == null ? Instant.now() : finishedAt);
        } catch (InterruptedException exception) {
            markFailed();
            Thread.currentThread().interrupt();
            log(task.name(), "Interrupted.");
            return new Result(task.name(), State.FAILED, exitCode,
                    "Task execution interrupted.", Instant.now());
        } finally {
            if (taskLog != null) {
                try {
                    taskLog.close();
                } catch (IOException exception) {
                    markFailed();
                    log(task.name(), "Could not close persistent log: "
                            + exception.getMessage());
                }
            }
            if (process != null) {
                if (process.isAlive()) {
                    terminate(process.toHandle());
                }
                processes.remove(process.toHandle());
            }
        }
    }

    private Result runDaemon(Task task, ExecutorService pool) {
        try {
            synchronized (gate) {
                if (failed) {
                    return new Result(task.name(), State.SKIPPED,
                            null, null, Instant.now());
                }
                history.started(task.name());
                daemons.start(task);
                log(task.name(), "Daemon started; waiting for readiness.");
            }
            daemons.awaitReady(task.name(), pool);
            log(task.name(), "Daemon ready. shutdownHook="
                    + task.daemon().shutdownHook());
            return new Result(task.name(), State.READY, null, null, null);
        } catch (IOException exception) {
            markFailed();
            log(task.name(), "Daemon failed: " + exception.getMessage());
            return new Result(task.name(), State.FAILED, null,
                    exception.getMessage(), Instant.now());
        } catch (InterruptedException exception) {
            markFailed();
            Thread.currentThread().interrupt();
            return new Result(task.name(), State.FAILED, null,
                    "Daemon startup interrupted.", Instant.now());
        }
    }

    private int recordDaemonFailures(Set<String> reported,
            Set<String> succeeded) throws IOException {
        var count = 0;
        for (var entry : daemons.failures().entrySet()) {
            if (reported.add(entry.getKey())) {
                markFailed();
                succeeded.remove(entry.getKey());
                log(entry.getKey(), entry.getValue());
                history.completed(entry.getKey(), RunHistory.State.FAILED,
                        null, entry.getValue(), Instant.now());
                count++;
            }
        }
        return count;
    }

    private void cleanupHooks() {
        synchronized (cleanupGate) {
            try {
                daemons.stopHooks(plan);
            } catch (IOException exception) {
                markFailed();
                log("pulse", "Daemon cleanup failed: "
                        + exception.getMessage());
            } catch (InterruptedException exception) {
                markFailed();
                Thread.currentThread().interrupt();
                log("pulse", "Daemon cleanup interrupted.");
            }
        }
    }

    private void markFailed() {
        synchronized (gate) {
            failed = true;
        }
    }

    private void cancel() {
        markFailed();
        try {
            history.ended(RunHistory.State.CANCELLED);
        } catch (IOException exception) {
            log("pulse", "Could not persist cancellation: "
                    + exception.getMessage());
        }
        processes.stream().toList().forEach(PulseExecutor::terminate);
        cleanupHooks();
    }

    private static void terminate(ProcessHandle process) {
        process.descendants().toList().reversed()
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void log(String name, String message) {
        synchronized (out) {
            out.println("[" + name + "] " + message);
            out.flush();
        }
    }
}
