package io.github.devpulse.server;

import java.nio.file.Path;

/**
 * Files the server owns under the user's devpulse home.
 *
 * <p>Kept as a record rather than static constants so a test can point the state and database files
 * at a temporary directory without touching the real home.
 */
public record ServerPaths(Path home, Path state, Path database) {

    /** The default layout under {@code ~/.devpulse}. */
    public static ServerPaths defaults() {
        return of(Path.of(System.getProperty("user.home"), ".devpulse"));
    }

    public static ServerPaths of(Path home) {
        return new ServerPaths(home, home.resolve("server.json"), home.resolve("server.mv.db"));
    }
}
