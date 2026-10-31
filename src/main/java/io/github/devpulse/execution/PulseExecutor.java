package io.github.devpulse.execution;

import io.github.devpulse.environment.BashShell;
import io.github.devpulse.execution.PulseConfiguration.Task;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private final Object gate = new Object();
    private final Set<ProcessHandle> processes = ConcurrentHashMap.newKeySet();
    private volatile boolean failed;

    private enum State { SUCCESS, FAILED, SKIPPED }

    private record Result(String name, State state) {}

    public PulseExecutor(BashShell bash, Path directory, PrintWriter out) {
        this.bash = bash;
        this.directory = directory;
        this.out = out;
    }

    public int execute(TaskPlan plan, int jobs) throws InterruptedException {
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        var completions = new ExecutorCompletionService<Result>(pool);
        var started = new HashSet<String>();
        var succeeded = new HashSet<String>();
        var active = 0;
        var failures = 0;
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
            }
            var skipped = plan.tasks().size() - succeeded.size() - failures;
            log("pulse", "Finished: " + succeeded.size() + " succeeded, "
                    + failures + " failed, " + skipped + " skipped.");
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
        try {
            synchronized (gate) {
                if (failed) {
                    return new Result(task.name(), State.SKIPPED);
                }
                try {
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
            var output = pool.submit(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log(task.name(), line);
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
            if (code != 0) {
                markFailed();
            }
            output.get();
            if (code == 0) {
                input.get();
            }
            log(task.name(), "Finished with exit code " + code + ".");
            return new Result(task.name(),
                    code == 0 ? State.SUCCESS : State.FAILED);
        } catch (IOException | ExecutionException exception) {
            markFailed();
            log(task.name(), "Failed: " + exception.getMessage());
            return new Result(task.name(), State.FAILED);
        } catch (InterruptedException exception) {
            markFailed();
            Thread.currentThread().interrupt();
            log(task.name(), "Interrupted.");
            return new Result(task.name(), State.FAILED);
        } finally {
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
