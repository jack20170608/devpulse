package io.github.devpulse.jira;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

public final class JiraClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String authorization;
    private final String api;
    private final boolean cloud;
    private final int apiVersion;

    public JiraClient(JiraConfiguration configuration, String token)
            throws IOException {
        var url = configuration.url();
        var mode = configuration.mode();
        if (token == null || token.isBlank()) {
            throw new IOException("Set JIRA_API_TOKEN "
                    + "in the environment.");
        }
        if (token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("JIRA_API_TOKEN must not "
                    + "contain control characters.");
        }
        try {
            base = URI.create(url.endsWith("/") ? url : url + "/");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Jira configuration URL.");
        }
        if (base.getHost() == null || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null
                || !("https".equalsIgnoreCase(base.getScheme())
                || ("http".equalsIgnoreCase(base.getScheme())
                && ("localhost".equalsIgnoreCase(base.getHost())
                || "127.0.0.1".equals(base.getHost()))))) {
            throw new IOException("Jira URL must use HTTPS "
                    + "(HTTP is allowed only for loopback testing).");
        }
        if (!mode.equals("cloud") && !mode.equals("dc")) {
            throw new IOException("Jira configuration mode must be cloud or dc.");
        }
        cloud = mode.equals("cloud");
        apiVersion = configuration.apiVersion();
        if (apiVersion != 2 && apiVersion != 3) {
            throw new IOException("Jira configuration apiVersion "
                    + "must be the number 2 or 3.");
        }
        api = "rest/api/" + apiVersion + "/";
        if (cloud) {
            var email = configuration.email();
            if (email == null || email.isBlank()) {
                throw new IOException("Jira configuration requires "
                        + "an email for Cloud.");
            }
            authorization = "Basic " + Base64.getEncoder().encodeToString(
                    (email + ":" + token).getBytes(StandardCharsets.UTF_8));
        } else {
            authorization = "Bearer " + token;
        }
    }

    public boolean cloud() {
        return cloud;
    }

    public int apiVersion() {
        return apiVersion;
    }

    public JsonObject myself() throws IOException {
        return object(request("GET", "myself", null));
    }

    public JsonObject issue(String key) throws IOException {
        return object(request("GET", "issue/" + segment(key)
                + "?fields=*all", null));
    }

    public JsonObject editMeta(String key) throws IOException {
        return object(request("GET", "issue/" + segment(key)
                + "/editmeta", null));
    }

    public JsonObject create(JsonObject fields) throws IOException {
        var body = new JsonObject();
        body.add("fields", fields);
        return object(request("POST", "issue", body));
    }

    public JsonObject search(String jql, int limit, Integer startAt,
            String nextPageToken) throws IOException {
        if (jql == null || jql.isBlank()) {
            throw new IOException("Search JQL must not be blank.");
        }
        if (limit < 1 || limit > 100) {
            throw new IOException("--limit must be between 1 and 100.");
        }
        if (startAt != null && startAt < 0) {
            throw new IOException("--start-at must not be negative.");
        }
        if (cloud && startAt != null) {
            throw new IOException("Cloud uses --next-page-token, "
                    + "not --start-at.");
        }
        if (!cloud && nextPageToken != null) {
            throw new IOException("Data Center uses --start-at, "
                    + "not --next-page-token.");
        }
        if (nextPageToken != null && nextPageToken.isBlank()) {
            throw new IOException("--next-page-token must not be blank.");
        }
        var body = new JsonObject();
        body.addProperty("jql", jql);
        body.addProperty("maxResults", limit);
        var fields = new JsonArray();
        for (var field : new String[]{"summary", "status", "issuetype",
                "priority", "assignee", "updated"}) {
            fields.add(field);
        }
        body.add("fields", fields);
        if (cloud) {
            if (nextPageToken != null) {
                body.addProperty("nextPageToken", nextPageToken);
            }
        } else {
            body.addProperty("startAt", startAt == null ? 0 : startAt);
        }
        return object(request("POST", cloud ? "search/jql" : "search",
                body));
    }

    public void edit(String key, JsonObject fields) throws IOException {
        edit(key, fields, new JsonObject());
    }

    public void edit(String key, JsonObject fields, JsonObject updates)
            throws IOException {
        var body = new JsonObject();
        body.add("fields", fields);
        if (!updates.isEmpty()) {
            body.add("update", updates);
        }
        request("PUT", "issue/" + segment(key), body);
    }

    public JsonObject transitions(String key) throws IOException {
        return object(request("GET", "issue/" + segment(key)
                + "/transitions", null));
    }

    public void transition(String key, String id) throws IOException {
        var body = new JsonObject();
        var transition = new JsonObject();
        transition.addProperty("id", id);
        body.add("transition", transition);
        request("POST", "issue/" + segment(key) + "/transitions", body);
    }

    public void comment(String key, JsonElement body) throws IOException {
        var data = new JsonObject();
        data.add("body", body);
        request("POST", "issue/" + segment(key) + "/comment", data);
    }

    public JsonObject comment(String key, String id) throws IOException {
        return object(request("GET", "issue/" + segment(key)
                + "/comment/" + numeric(id), null));
    }

    public void editComment(String key, String id, JsonElement body)
            throws IOException {
        var data = new JsonObject();
        data.add("body", body);
        request("PUT", "issue/" + segment(key) + "/comment/"
                + numeric(id), data);
    }

    public void attach(String key, Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 25_000_000) {
            throw new IOException("Attachment must be a regular file "
                    + "of at most 25 MB.");
        }
        var boundary = "devpulse-" + java.util.UUID.randomUUID();
        var name = file.getFileName().toString();
        if (name.contains("\r") || name.contains("\n")
                || name.contains("\"")) {
            throw new IOException("Unsafe attachment filename.");
        }
        var prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; "
                + "filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var request = builder("issue/" + segment(key) + "/attachments")
                .header("X-Atlassian-Token", "no-check")
                .header("Content-Type", "multipart/form-data; boundary="
                        + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofFile(file),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
        send(request);
    }

    private JsonElement request(String method, String path, JsonObject data)
            throws IOException {
        var builder = builder(path);
        if (data == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            data.toString(), StandardCharsets.UTF_8));
        }
        try {
            var response = send(builder.build());
            return response.isBlank() ? new JsonObject()
                    : JsonParser.parseString(response);
        } catch (com.google.gson.JsonParseException exception) {
            throw new IOException("Jira returned invalid JSON.", exception);
        }
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(base.resolve(api + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorization)
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request) throws IOException {
        try {
            var response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Response bodies may contain private issue data.
                throw new IOException("Jira HTTP " + response.statusCode()
                        + " for " + request.method() + " "
                        + request.uri().getPath() + ".");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Jira request interrupted.", exception);
        }
    }

    private static JsonObject object(JsonElement element)
            throws IOException {
        if (element == null || !element.isJsonObject()) {
            throw new IOException("Expected a JSON object from Jira.");
        }
        return element.getAsJsonObject();
    }

    private static String segment(String value) throws IOException {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")
                && !value.matches("[0-9]+")) {
            throw new IOException("Invalid Jira issue key or ID.");
        }

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String numeric(String value) throws IOException {
        if (!value.matches("[0-9]+")) {
            throw new IOException("Invalid Jira comment ID.");
        }
        return value;
    }

}
