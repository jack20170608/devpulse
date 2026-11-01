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

public final class PulseExecutor {
    private final BashShell bash;
    private final Path directory;
    private final PrintWriter out;
    private final RunHistory.Writer history;
    private final Object gate = new Object();
    private final Set<ProcessHandle> processes = ConcurrentHashMap.newKeySet();
    private volatile boolean failed;

    private enum State { SUCCESS, FAILED, SKIPPED }

    private record Result(String name, State state, Integer exitCode,
            String error, Instant finishedAt) {}

    public PulseExecutor(BashShell bash, Path directory, PrintWriter out,
            RunHistory.Writer history) {
        this.bash = bash;
        this.directory = directory;
        this.out = out;
        this.history = history;
    }

    public int execute(TaskPlan plan, int jobs)
            throws IOException, InterruptedException {
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        var completions = new ExecutorCompletionService<Result>(pool);
        var started = new HashSet<String>();
        var succeeded = new HashSet<String>();
        var active = 0;
        var failures = 0;
        IOException persistenceFailure = null;
        var hook = Thread.ofPlatform().unstarted(this::cancel);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            while (true) {
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
                    result = completions.take().get();
                } catch (ExecutionException exception) {
                    cancel();
                    throw new IllegalStateException(
                            "Task execution failed unexpectedly.",
                            exception.getCause());
                }
                active--;
                switch (result.state()) {
                    case SUCCESS -> succeeded.add(result.name());
                    case FAILED -> failures++;
                    case SKIPPED -> { }
                }
                try {
                    history.completed(result.name(),
                            RunHistory.State.valueOf(result.state().name()),
                            result.exitCode(), result.error(),
                            result.finishedAt());
                } catch (IOException exception) {
                    markFailed();
                    persistenceFailure = exception;
                    log("pulse", "Could not persist task state: "
                            + exception.getMessage());
                }
            }
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
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    private Result run(Task task, ExecutorService pool) {
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
