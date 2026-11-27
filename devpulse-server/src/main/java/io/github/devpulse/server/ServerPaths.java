package io.github.devpulse.server;

import java.nio.file.Path;

/**
 * Files the server owns under the user's devpulse home.
 *
 * <p>Kept as a record rather than static constants so a test can point every path at a temporary
 * directory without touching the real home.
 */
public record ServerPaths(Path home, Path token, Path state) {

    /** The default layout under {@code ~/.devpulse}. */
    public static ServerPaths defaults() {
        return of(Path.of(System.getProperty("user.home"), ".devpulse"));
    }

    public static ServerPaths of(Path home) {
        return new ServerPaths(home, home.resolve("token"), home.resolve("server.json"));
    }
}
