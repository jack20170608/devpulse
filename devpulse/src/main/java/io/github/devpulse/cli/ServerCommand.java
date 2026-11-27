package io.github.devpulse.cli;

import io.github.devpulse.server.ServerBootstrap;
import io.github.devpulse.server.ServerPaths;
import io.github.devpulse.server.ServerSettings;
import io.github.devpulse.server.ServerState;
import io.github.devpulse.server.ServerToken;
import java.io.IOException;
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
 * <p>One instance per user: the state file records the port the OS assigned, so clients never need
 * a fixed port.
 */
@Command(
        name = "server",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Run the local server that executes tasks.",
        subcommands = {
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

    /** Start the server in the foreground; Ctrl+C stops it. */
    @Command(
            name = "run",
            mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Run the server in the foreground.")
    public static final class Run implements Callable<Integer> {
        @Spec private CommandSpec spec;

        @Option(
                names = "--directory",
                defaultValue = ".",
                description = "Project directory used for the doctor report (default: .).")
        private Path directory;

        @Option(
                names = "--port",
                defaultValue = "0",
                description = "Port to bind; 0 asks the OS (default: 0).")
        private int port;

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            ServerPaths paths;
            ServerSettings settings;
            try {
                paths = ServerPaths.defaults();
                var token = ServerToken.loadOrCreate(paths.token());
                settings =
                        new ServerSettings(
                                "127.0.0.1",
                                port,
                                token,
                                paths.state(),
                                directory.toAbsolutePath());
            } catch (IOException exception) {
                err.println("devpulse: " + exception.getMessage());
                return ExitCodes.INFRASTRUCTURE;
            }
            var latch = new CountDownLatch(1);
            ServerBootstrap.Running running;
            try {
                running = ServerBootstrap.start(settings);
            } catch (IOException exception) {
                err.println("devpulse: cannot start the server: " + exception.getMessage());
                return ExitCodes.INFRASTRUCTURE;
            }
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
                out.println("No server recorded. Start one with: devpulse server run");
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
            handle.get().destroy();
            out.println("Asked server " + recorded.pid() + " to stop.");
            return ExitCodes.OK;
        }
    }
}
