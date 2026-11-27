package io.github.devpulse.server;

import java.nio.file.Path;

/**
 * Everything the server needs before it starts.
 *
 * @param host the interface to bind; loopback only in v1
 * @param port {@code 0} asks the OS for a free port, which is then reported in the state file
 * @param token the bearer token clients must present
 * @param stateFile where the running server records pid, port and boot id
 * @param directory the project directory the doctor report inspects
 */
public record ServerSettings(String host, int port, String token, Path stateFile, Path directory) {

    public ServerSettings {
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalArgumentException("Server must bind loopback, not " + host);
        }
    }
}
