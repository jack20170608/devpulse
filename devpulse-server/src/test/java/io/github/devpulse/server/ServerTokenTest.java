package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the local token: generated once, then reused across server restarts. */
class ServerTokenTest {
    @TempDir Path home;

    @Test
    void createsAHexTokenOnFirstUse() throws IOException {
        var file = home.resolve("token");

        var token = ServerToken.loadOrCreate(file);

        assertThat(token).hasSize(64).matches("[0-9a-f]+");
        assertThat(Files.readString(file, StandardCharsets.UTF_8).trim()).isEqualTo(token);
    }

    @Test
    void reusesTheExistingToken() throws IOException {
        var file = home.resolve("token");

        var first = ServerToken.loadOrCreate(file);
        var second = ServerToken.loadOrCreate(file);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void refusesAnEmptyTokenFile() throws IOException {
        var file = home.resolve("token");
        Files.writeString(file, "  \n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ServerToken.loadOrCreate(file)).isInstanceOf(IOException.class);
    }
}
