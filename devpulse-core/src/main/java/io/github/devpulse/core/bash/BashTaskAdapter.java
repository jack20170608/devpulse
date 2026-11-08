package io.github.devpulse.core.bash;

import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagTask;
import io.github.devpulse.core.dag.TaskContext;
import io.github.devpulse.core.environment.BashShell;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public final class BashTaskAdapter {
    public static final class ExitFailure extends Exception {
        private final int exitCode;

        ExitFailure(int exitCode) {
            super("Bash exited with code " + exitCode + ".");
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private final BashShell bash;
    private final Path directory;
    private final PrintWriter out;
    private final RunHistory.Writer history;
    private final DaemonManager daemons;
    private final Object cleanupGate = new Object();

    public BashTaskAdapter(BashShell bash, Path directory, PrintWriter out) {
        this(bash, directory, out, null);
    }

    public BashTaskAdapter(BashShell bash, Path directory, PrintWriter out,
            RunHistory.Writer history) {
        this.bash = Objects.requireNonNull(bash, "bash");
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.out = Objects.requireNonNull(out, "out");
        this.history = history;
        daemons = history == null ? new DaemonManager(bash, this.directory)
                : new DaemonManager(history, bash, this.directory);
    }

    public DagTask<Integer> task(String command) {
        var script = script(command);
        return context -> execute(context.taskId(), script, context);
    }

    public DagDaemon<ProcessHandle> daemon(String command, BashTaskDefinition.Daemon configuration) {
        var script = script(command);
        Objects.requireNonNull(configuration, "configuration");
        script(configuration.readyCommand());
        if (configuration.stopCommand() != null) {
            script(configuration.stopCommand());
        }
        return context -> start(new BashTaskDefinition(context.taskId(), script,
                List.of(), configuration), context);
    }

    private Integer execute(String name, String command, TaskContext context) throws Exception {
        try {
            context.checkCancelled();
            if (history != null) {
                history.started(name);
            }
            try (var taskLog = history == null ? new BufferedWriter(Writer.nullWriter())
                    : history.openLog(name, context.attempt() > 1)) {
                context.checkCancelled();
                var process = bash.script(directory).redirectErrorStream(true).start();
                log(name, "Started.");
                return waitFor(name, command, context, process, taskLog);
            }
        } catch (IOException exception) {
            context.failRun(exception);
            throw exception;
        } catch (ExecutionException exception) {
            context.failRun(exception.getCause());
            if (exception.getCause() instanceof Error fatal) {
                throw fatal;
            }
            throw exception;
        }
    }

    private int waitFor(String name, String command, TaskContext context,
            Process process, BufferedWriter taskLog)
            throws IOException, InterruptedException, ExecutionException, ExitFailure {
        var io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var output = io.submit(() -> {
                readOutput(name, context, process, taskLog);
                return null;
            });
            var input = io.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write(command.getBytes(StandardCharsets.UTF_8));
                    stream.write('\n');
                }
                return null;
            });
            var code = process.waitFor();
            output.get();
            if (history != null) {
                history.processExited(name, code);
            }
            if (code == 0) {
                try {
                    input.get();
                } catch (ExecutionException exception) {
                    // A successful early exit may close stdin before all input is written.
                    if (!(exception.getCause() instanceof IOException)) {
                        throw exception;
                    }
                }
            }
            log(name, "Finished with exit code " + code + ".");
            if (code != 0) {
                throw new ExitFailure(code);
            }
            return code;
        } finally {
            terminate(process.toHandle());
            io.shutdownNow();
            io.close();
        }
    }

    private DagDaemon.Handle<ProcessHandle> start(BashTaskDefinition task, TaskContext context)
            throws IOException, InterruptedException {
        try {
            context.checkCancelled();
            if (history != null) {
                history.started(task.name());
            }
            context.checkCancelled();
            var handle = daemons.start(task, context.daemonOptions(), context.attempt());
            log(task.name(), "Daemon started; waiting for readiness.");
            return new DagDaemon.Handle<>() {
                @Override
                public ProcessHandle value() {
                    return handle.value();
                }

                @Override
                public DagDaemon.Health check() throws Exception {
                    try {
                        return handle.check();
                    } catch (IOException exception) {
                        context.failRun(exception);
                        throw exception;
                    }
                }
            };
        } catch (IOException exception) {
            context.failRun(exception);
            throw exception;
        }
    }

    private void readOutput(String name, TaskContext context, Process process, BufferedWriter taskLog)
            throws IOException, InterruptedException {
        try (var stream = process.getInputStream();
                var line = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            var previousCarriageReturn = false;
            while (true) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Log reader interrupted.");
                }
                var available = stream.available();
                if (available == 0) {
                    // Descendants may retain the pipe after Bash exits; never wait for their EOF.
                    if (!process.isAlive()) {
                        if (stream.available() == 0) {
                            break;
                        }
                        continue;
                    }
                    Thread.sleep(10);
                    continue;
                }
                var count = stream.read(buffer, 0, Math.min(available, buffer.length));
                if (count < 0) {
                    break;
                }
                for (var index = 0; index < count; index++) {
                    if (previousCarriageReturn && buffer[index] == '\n') {
                        previousCarriageReturn = false;
                        continue;
                    }
                    previousCarriageReturn = buffer[index] == '\r';
                    if (buffer[index] == '\n' || previousCarriageReturn) {
                        writeLine(name, taskLog, line);
                    } else {
                        line.write(buffer[index]);
                    }
                }
            }
            if (line.size() > 0) {
                writeLine(name, taskLog, line);
            }
        } catch (IOException exception) {
            context.failRun(exception);
            throw exception;
        }
    }

    private void writeLine(String task, BufferedWriter taskLog, ByteArrayOutputStream bytes)
            throws IOException {
        var line = bytes.toString(StandardCharsets.UTF_8);
        bytes.reset();
        log(task, line);
        taskLog.write(line);
        taskLog.newLine();
        taskLog.flush();
    }

    private void terminate(ProcessHandle process) {
        synchronized (cleanupGate) {
            var descendants = process.descendants().toList().reversed();
            descendants.forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void log(String task, String message) {
        synchronized (out) {
            out.println("[" + task + "] " + message);
            out.flush();
        }
    }

    private static String script(String command) {
        Objects.requireNonNull(command, "command");
        if (command.isBlank()) {
            throw new IllegalArgumentException("Bash scripts must not be blank.");
        }
        return command.replace("\r\n", "\n");
    }
}
