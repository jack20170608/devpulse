package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the workflow registry API: push creates a revision, a stale base revision is refused with
 * the current head, and the list/detail reads expose what was written.
 */
class WorkflowApiTest {
    private static final String CONTENT = "tasks:\n  - id: check\n    spec:\n      run: 'true'\n";

    @TempDir Path directory;

    private ServerBootstrap.Running start() throws Exception {
        return ServerBootstrap.start(
                new ServerSettings(
                        "127.0.0.1",
                        0,
                        directory.resolve("server.json"),
                        directory,
                        directory.resolve("server.mv.db")));
    }

    private HttpResponse<String> get(ServerBootstrap.Running running, String path)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + path))
                        .header(Auth.CLIENT_HEADER, "test")
                        .GET()
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(ServerBootstrap.Running running, String path, String body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + path))
                        .header(Auth.CLIENT_HEADER, "test")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String push(Integer baseRevision, String content) {
        var base = baseRevision == null ? "" : ",\"baseRevision\":" + baseRevision;
        return "{\"project\":"
                + quote(directory.toString())
                + ",\"content\":"
                + quote(content)
                + ",\"name\":\"Build\""
                + base
                + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void pushesAWorkflowAndReadsItBack() throws Exception {
        try (var running = start()) {
            var pushed = put(running, "/api/workflows/build", push(null, CONTENT));

            assertThat(pushed.statusCode()).isEqualTo(200);
            var head =
                    JsonParser.parseString(pushed.body())
                            .getAsJsonObject()
                            .getAsJsonObject("workflow");
            assertThat(head.get("revision").getAsInt()).isEqualTo(1);
            assertThat(head.get("hash").getAsString()).hasSize(64);
            assertThat(head.get("content").getAsString()).isEqualTo(CONTENT);

            var listed = get(running, "/api/workflows?project=" + encode(directory));
            assertThat(listed.statusCode()).isEqualTo(200);
            assertThat(
                            JsonParser.parseString(listed.body())
                                    .getAsJsonObject()
                                    .getAsJsonArray("workflows"))
                    .hasSize(1);

            var detail = get(running, "/api/workflows/build?project=" + encode(directory));
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(
                            JsonParser.parseString(detail.body())
                                    .getAsJsonObject()
                                    .getAsJsonObject("workflow")
                                    .get("content")
                                    .getAsString())
                    .isEqualTo(CONTENT);
        }
    }

    @Test
    void refusesAStaleBaseRevisionWithTheCurrentHead() throws Exception {
        try (var running = start()) {
            put(running, "/api/workflows/build", push(null, CONTENT));

            var stale = put(running, "/api/workflows/build", push(0, CONTENT));

            assertThat(stale.statusCode()).isEqualTo(409);
            var error =
                    JsonParser.parseString(stale.body()).getAsJsonObject().getAsJsonObject("error");
            assertThat(error.get("code").getAsString()).isEqualTo("WORKFLOW_CONFLICT");
            assertThat(error.get("currentRevision").getAsInt()).isEqualTo(1);
        }
    }

    @Test
    void advancesTheRevisionAndListsHistoryNewestFirst() throws Exception {
        try (var running = start()) {
            put(running, "/api/workflows/build", push(null, CONTENT));
            put(running, "/api/workflows/build", push(1, CONTENT + "# edited\n"));

            var revisions =
                    get(running, "/api/workflows/build/revisions?project=" + encode(directory));

            assertThat(revisions.statusCode()).isEqualTo(200);
            var list =
                    JsonParser.parseString(revisions.body())
                            .getAsJsonObject()
                            .getAsJsonArray("revisions");
            assertThat(list).hasSize(2);
            assertThat(list.get(0).getAsJsonObject().get("revision").getAsInt()).isEqualTo(2);
            assertThat(list.get(1).getAsJsonObject().get("revision").getAsInt()).isEqualTo(1);
        }
    }

    @Test
    void reportsAnUnknownProjectAsTargetInvalid() throws Exception {
        try (var running = start()) {
            var response =
                    get(running, "/api/workflows?project=" + encode(directory.resolve("missing")));

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("TARGET_INVALID");
        }
    }

    @Test
    void refusesAWorkflowRequestWithoutTheClientHeader() throws Exception {
        try (var running = start()) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + running.port()
                                                    + "/api/workflows?project="
                                                    + encode(directory)))
                            .GET()
                            .build();
            var response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("CLIENT_HEADER_REQUIRED");
        }
    }

    private static String encode(Path path) {
        return java.net.URLEncoder.encode(path.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
