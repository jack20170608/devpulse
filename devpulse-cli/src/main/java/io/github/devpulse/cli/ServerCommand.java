package io.github.devpulse.cli;

import io.github.devpulse.server.ServerBootstrap;
import io.github.devpulse.server.ServerPaths;
import io.github.devpulse.server.ServerSettings;
import io.github.devpulse.server.ServerState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Run and inspect the local server.
 *
 * <p>One instance per user on a fixed port: the state file records the pid and port, so a second
 * {@code start} can tell a live server from a stale record.
 */
@Command(
        name = "server",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Run the local web server that executes tasks.",
        subcommands = {
            ServerCommand.Start.class,
            ServerCommand.Run.class,
            ServerCommand.Status.class,
            ServerCommand.Stop.class
        })
public final class ServerCommand implements Runnable {
    @Spec private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Shared by the two forms that actually bind the port. */
    private abstract static class Binding implements Callable<Integer> {
        @Spec CommandSpec spec;

        @Option(
                names = "--directory",
                defaultValue = ".",
                description = "Project directory used for the doctor report (default: .).")
        Path directory;

        @Option(
                names = "--port",
                defaultValue = "" + ServerSettings.DEFAULT_PORT,
                description = "Port to bind (default: ${DEFAULT-VALUE}).")
        int port;

        Integer fail(String message, int code) {
            spec.commandLine().getErr().println("devpulse: " + message);
            return code;
        }
    }

    /**
     * The command that re-launches this program.
     *
     * <p>A native binary is its own command. A JAR must be re-launched through the same Java
     * runtime, because the process command alone would be {@code java} without the {@code -jar}.
     */
    private static java.util.Optional<java.util.List<String>> selfCommand() {
        var command = ProcessHandle.current().info().command();
        if (command.isPresent() && !command.get().endsWith("java")) {
            return java.util.Optional.of(java.util.List.of(command.get()));
        }
        try {
            var location = ServerCommand.class.getProtectionDomain().getCodeSource().getLocation();
            var jar = java.nio.file.Path.of(location.toURI());
            var javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java");
            if (java.nio.file.Files.isRegularFile(javaBin)
                    && java.nio.file.Files.isRegularFile(jar)) {
                return java.util.Optional.of(
                        java.util.List.of(javaBin.toString(), "-jar", jar.toString()));
            }
        } catch (RuntimeException | java.net.URISyntaxException ignored) {
            // Fall through: the caller reports that start needs the foreground form.
        }
        return java.util.Optional.empty();
    }

    /** Start in the background and return once the server answers. */
    @Command(
            name = "start",
            mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Start the server in the background.")
    public static final class Start extends Binding {
        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var paths = ServerPaths.defaults();
            var existing = ServerState.read(paths.state());
            if (existing.isPresent()
                    && ProcessHandle.of(existing.get().pid())
                            .map(ProcessHandle::isAlive)
                            .orElse(false)) {
                out.println(
                        "Server already running on http://127.0.0.1:"
                                + existing.get().port()
                                + " (pid "
                                + existing.get().pid()
                                + ")");
                return ExitCodes.OK;
            }
            var self = selfCommand();
            if (self.isEmpty()) {
                return fail(
                        "cannot locate this executable; run 'devpulse server run' in the foreground",
                        ExitCodes.INFRASTRUCTURE);
            }
            try {
                Files.createDirectories(paths.home());
                var log = paths.home().resolve("server.log");
                var command = new java.util.ArrayList<>(self.get());
                command.add("server");
                command.add("run");
                command.add("--port");
                command.add(Integer.toString(port));
                command.add("--directory");
                command.add(directory.toAbsolutePath().toString());
                var process =
                        new ProcessBuilder(command)
                                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                                .redirectErrorStream(true)
                                .start();
                var deadline = System.nanoTime() + 10_000_000_000L;
                while (System.nanoTime() < deadline) {
                    var state = ServerState.read(paths.state());
                    if (state.isPresent() && state.get().pid() == process.pid()) {
                        out.println(
                                "devpulse server started on http://127.0.0.1:"
                                        + state.get().port()
                                        + " (pid "
                                        + process.pid()
                                        + ")");
                        return ExitCodes.OK;
                    }
                    if (!process.isAlive()) {
                        return fail(
                                "server exited during startup; see "
                                        + paths.home().resolve("server.log"),
                                ExitCodes.INFRASTRUCTURE);
                    }
                    Thread.sleep(100);
                }
                return fail(
                        "server did not become ready; see " + paths.home().resolve("server.log"),
                        ExitCodes.INFRASTRUCTURE);
            } catch (IOException exception) {
                return fail(
                        "cannot start the server: " + exception.getMessage(),
                        ExitCodes.INFRASTRUCTURE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ExitCodes.INTERRUPTED;
            }
        }
    }

    /** Start in the foreground; Ctrl+C stops it. */
    @Command(
            name = "run",
            mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Run the server in the foreground.")
    public static final class Run extends Binding {
        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var paths = ServerPaths.defaults();
            ServerBootstrap.Running running;
            try {
                running =
                        ServerBootstrap.start(
                                new ServerSettings(
                                        "127.0.0.1",
                                        port,
                                        paths.state(),
                                        directory.toAbsolutePath()));
            } catch (IOException exception) {
                return fail(
                        "cannot start the server: " + exception.getMessage(),
                        ExitCodes.INFRASTRUCTURE);
            }
            var latch = new CountDownLatch(1);
            var shutdown =
                    Thread.ofPlatform()
                            .unstarted(() -> {
                                running.close();
                                latch.countDown();
                            });
            Runtime.getRuntime().addShutdownHook(shutdown);
            out.println(
                    "devpulse server listening on http://127.0.0.1:"
                            + running.port()
                            + " (boot "
                            + running.bootId()
                            + ")");
            out.flush();
            try {
                latch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return ExitCodes.OK;
        }
    }

    /** Report whether a server is recorded as running. */
    @Command(
            name = "status",
            mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Show whether a server is running.")
    public static final class Status implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var state = ServerState.read(ServerPaths.defaults().state());
            if (state.isEmpty()) {
                out.println("No server recorded. Start one with: devpulse server start");
                return ExitCodes.OK;
            }
            var recorded = state.get();
            var alive = ProcessHandle.of(recorded.pid()).map(ProcessHandle::isAlive).orElse(false);
            out.println(
                    (alive ? "Running" : "Not running (stale state)")
                            + ": pid "
                            + recorded.pid()
                            + ", port "
                            + recorded.port()
                            + ", version "
                            + recorded.version()
                            + ", started "
                            + recorded.startedAt());
            if (alive) {
                out.println("URL: http://127.0.0.1:" + recorded.port() + "/");
            }
            return ExitCodes.OK;
        }
    }

    /** Ask a recorded server to stop; the process gets a normal termination request. */
    @Command(
            name = "stop",
            mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Stop the recorded server.")
    public static final class Stop implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            var paths = ServerPaths.defaults();
            var state = ServerState.read(paths.state());
            if (state.isEmpty()) {
                out.println("No server recorded.");
                return ExitCodes.OK;
            }
            var recorded = state.get();
            var handle = ProcessHandle.of(recorded.pid());
            if (handle.isEmpty() || !handle.get().isAlive()) {
                out.println("Server is not running; clearing stale state.");
                try {
                    recorded.delete(paths.state());
                } catch (IOException exception) {
                    err.println("devpulse: " + exception.getMessage());
                    return ExitCodes.INFRASTRUCTURE;
                }
                return ExitCodes.OK;
            }
            var process = handle.get();
            process.destroy();
            try {
                process.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                recorded.delete(paths.state());
                out.println("Stopped server " + recorded.pid() + ".");
            } catch (java.util.concurrent.TimeoutException exception) {
                out.println(
                        "Asked server " + recorded.pid() + " to stop; it is still shutting down.");
            } catch (java.util.concurrent.ExecutionException exception) {
                out.println("Server " + recorded.pid() + " exited with an error.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ExitCodes.INTERRUPTED;
            } catch (IOException exception) {
                err.println("devpulse: " + exception.getMessage());
                return ExitCodes.INFRASTRUCTURE;
            }
            return ExitCodes.OK;
        }
    }
}
