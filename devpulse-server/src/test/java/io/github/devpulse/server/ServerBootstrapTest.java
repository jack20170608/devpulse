package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the loopback contract: an authenticated request is answered, an anonymous one is not. */
class ServerBootstrapTest {
    @TempDir Path directory;

    private static HttpResponse<String> get(
            ServerBootstrap.Running running, String path, String token) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesHealthWhenTheTokenMatches() throws Exception {
        var settings =
                new ServerSettings(
                        "127.0.0.1", 0, "test-token", directory.resolve("server.json"), directory);

        try (var running = ServerBootstrap.start(settings)) {
            var response = get(running, "/api/health", "test-token");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"view\":\"health\"");
            assertThat(response.body()).contains(running.bootId());
        }
    }

    @Test
    void refusesRequestsWithoutTheToken() throws Exception {
        var settings =
                new ServerSettings(
                        "127.0.0.1", 0, "test-token", directory.resolve("server.json"), directory);

        try (var running = ServerBootstrap.start(settings)) {
            var response = get(running, "/api/health", "wrong-token");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHORIZED");
        }
    }

    @Test
    void recordsItsPortInTheStateFile() throws Exception {
        var stateFile = directory.resolve("server.json");
        var settings = new ServerSettings("127.0.0.1", 0, "test-token", stateFile, directory);

        try (var running = ServerBootstrap.start(settings)) {
            var state = ServerState.read(stateFile).orElseThrow();

            assertThat(state.port()).isEqualTo(running.port());
            assertThat(state.bootId()).isEqualTo(running.bootId());
        }
    }
}
