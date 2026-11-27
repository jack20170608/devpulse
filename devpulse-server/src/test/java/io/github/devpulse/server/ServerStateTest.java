package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the rendezvous file a client reads to find the server. */
class ServerStateTest {
    @TempDir Path home;

    @Test
    void roundTripsThroughTheStateFile() throws IOException {
        var file = home.resolve("server.json");
        var state =
                new ServerState(
                        4242L, 51234, "boot-1", "0.1.5", Instant.parse("2026-11-27T00:00:00Z"));

        state.write(file);

        assertThat(ServerState.read(file)).contains(state);
    }

    @Test
    void reportsNoStateWhenTheFileIsMissing() {
        assertThat(ServerState.read(home.resolve("absent.json"))).isEmpty();
    }

    @Test
    void reportsNoStateWhenTheFileIsUnreadable() throws IOException {
        var file = home.resolve("server.json");
        Files.writeString(file, "not json");

        assertThat(ServerState.read(file)).isEmpty();
    }
}
