package io.github.devpulse.server;

import java.nio.file.Path;

/**
 * Everything the server needs before it starts.
 *
 * @param host the interface to bind; loopback only
 * @param port the port to bind; {@code 12580} by default, and a busy port is an error rather than a
 *     silent fallback so clients always know where to connect. Zero asks the OS for a port and is
 *     used only by tests.
 * @param stateFile where the running server records pid, port and boot id
 * @param directory the project directory the doctor report inspects
 * @param database the central workflow database the server owns
 */
public record ServerSettings(String host, int port, Path stateFile, Path directory, Path database) {

    /** The port a personal local server uses unless told otherwise. */
    public static final int DEFAULT_PORT = 12580;

    /** A test-friendly form that keeps the database next to the state file. */
    public ServerSettings(String host, int port, Path stateFile, Path directory) {
        this(
                host,
                port,
                stateFile,
                directory,
                stateFile.toAbsolutePath().getParent().resolve("server.mv.db"));
    }

    public ServerSettings {
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalArgumentException("Server must bind loopback, not " + host);
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535, not " + port);
        }
        if (database == null) {
            throw new IllegalArgumentException("Server needs a database path");
        }
    }
}
