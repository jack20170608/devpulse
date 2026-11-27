package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the loopback contract: a request carrying the client header is answered, one without it is
 * not.
 */
class ServerBootstrapTest {
    @TempDir Path directory;

    private static HttpResponse<String> get(ServerBootstrap.Running running, String path)
            throws Exception {
        return get(running, path, "test");
    }

    private static HttpResponse<String> get(
            ServerBootstrap.Running running, String path, String clientHeader) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + path))
                        .GET();
        if (clientHeader != null) {
            builder.header(Auth.CLIENT_HEADER, clientHeader);
        }
        return HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesHealthWhenTheClientHeaderIsPresent() throws Exception {
        var settings =
                new ServerSettings("127.0.0.1", 0, directory.resolve("server.json"), directory);

        try (var running = ServerBootstrap.start(settings)) {
            var response = get(running, "/api/health");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"view\":\"health\"");
            assertThat(response.body()).contains(running.bootId());
        }
    }

    @Test
    void refusesRequestsWithoutTheClientHeader() throws Exception {
        var settings =
                new ServerSettings("127.0.0.1", 0, directory.resolve("server.json"), directory);

        try (var running = ServerBootstrap.start(settings)) {
            var response = get(running, "/api/health", null);

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("CLIENT_HEADER_REQUIRED");
        }
    }

    @Test
    void recordsItsPortInTheStateFile() throws Exception {
        var stateFile = directory.resolve("server.json");
        var settings = new ServerSettings("127.0.0.1", 0, stateFile, directory);

        try (var running = ServerBootstrap.start(settings)) {
            var state = ServerState.read(stateFile).orElseThrow();

            assertThat(state.port()).isEqualTo(running.port());
            assertThat(state.bootId()).isEqualTo(running.bootId());
        }
    }
}
