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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

public final class JiraClient {
    public record AttachmentUpload(JsonArray attachments, String sha256,
            long size, String contentType, String filename) {
    }

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
        var mode = configuration.mode();
        if (token == null || token.isBlank()) {
            throw new IOException("Set JIRA_API_TOKEN "
                    + "in the environment.");
        }
        if (token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("JIRA_API_TOKEN must not "
                    + "contain control characters.");
        }
        base = JiraConfiguration.validateUrl(configuration.url());
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

    public JsonArray assignableUsers(String key, String query)
            throws IOException {
        var result = request("GET", "user/assignable/search?issueKey="
                + segment(key) + (cloud ? "&query=" : "&username=")
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&maxResults=50", null);
        if (!result.isJsonArray()) {
            throw new IOException("Expected a JSON array from Jira.");
        }
        return new AttachmentUpload(result.getAsJsonArray(),
                sha256(content), content.length, contentType, name);
    }

    public void assign(String key, String user) throws IOException {
        var body = new JsonObject();
        var identifier = cloud ? "accountId" : "name";
        if (user == null) {
            body.add(identifier, com.google.gson.JsonNull.INSTANCE);
        } else {
            body.addProperty(identifier, user);
        }
        request("PUT", "issue/" + segment(key) + "/assignee", body);
    }

    public JsonObject create(JsonObject fields) throws IOException {
        var body = new JsonObject();
        body.add("fields", fields);
        var response = request("POST", "issue", body);
        try {
            var result = object(response);
            if (!result.has("key") || !result.get("key").isJsonPrimitive()
                    || !result.getAsJsonPrimitive("key").isString()
                    || result.get("key").getAsString().isBlank()) {
                throw new IOException("Missing issue key.");
            }
            return result;
        } catch (IOException exception) {
            throw new JiraRequestException(
                    "Jira create response has no usable issue key; inspect Jira before retrying.",
                    "OUTCOME_UNKNOWN", null, true, false, exception);
        }
    }

    public JsonObject search(String jql, int limit, Integer startAt,
            String nextPageToken) throws IOException {
        return search(jql, limit, startAt, nextPageToken,
                java.util.List.of("summary", "status", "issuetype",
                        "priority", "assignee", "updated"));
    }

    public JsonObject search(String jql, int limit, Integer startAt,
            String nextPageToken, java.util.List<String> requestedFields)
            throws IOException {
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
        for (var field : requestedFields) {
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
        return transitions(key, false);
    }

    public JsonObject transitions(String key, boolean includeFields)
            throws IOException {
        return object(request("GET", "issue/" + segment(key)
                + "/transitions"
                + (includeFields ? "?expand=transitions.fields" : ""), null));
    }

    public void transition(String key, String id) throws IOException {
        transition(key, id, new JsonObject(), new JsonObject());
    }

    public void transition(String key, String id, JsonObject fields,
            JsonObject updates) throws IOException {
        var body = new JsonObject();
        var transition = new JsonObject();
        transition.addProperty("id", id);
        body.add("transition", transition);
        if (!fields.isEmpty()) {
            body.add("fields", fields);
        }
        if (!updates.isEmpty()) {
            body.add("update", updates);
        }
        request("POST", "issue/" + segment(key) + "/transitions", body);
    }

    public JsonObject comment(String key, JsonElement body) throws IOException {
        var data = new JsonObject();
        data.add("body", body);
        return object(request("POST", "issue/" + segment(key)
                + "/comment", data));
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

    public JsonArray attachments(String key) throws IOException {
        var issue = object(request("GET", "issue/" + segment(key)
                + "?fields=attachment", null));
        var fields = issue.get("fields");
        if (fields == null || !fields.isJsonObject()) {
            throw new IOException("Jira returned no attachment fields.");
        }
        var attachments = fields.getAsJsonObject().get("attachment");
        if (attachments == null || !attachments.isJsonArray()) {
            throw new IOException("Jira returned an invalid attachment list.");
        }
        return attachments.getAsJsonArray();
    }

    public AttachmentUpload attach(String key, Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 25_000_000) {
            throw new IOException("Attachment must be a regular file "
                    + "of at most 25 MB.");
        }
        byte[] content;
        try (var input = Files.newInputStream(file)) {
            content = input.readNBytes(25_000_001);
        }
        if (content.length > 25_000_000) {
            throw new IOException("Attachment must be a regular file "
                    + "of at most 25 MB.");
        }
        var boundary = "devpulse-" + java.util.UUID.randomUUID();
        var name = file.getFileName().toString();
        if (name.contains("\r") || name.contains("\n")
                || name.contains("\"")) {
            throw new IOException("Unsafe attachment filename.");
        }
        var contentType = Files.probeContentType(file);
        if (contentType == null
                || !contentType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
            contentType = "application/octet-stream";
        }
        var prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; "
                + "filename=\"" + name + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var request = builder("issue/" + segment(key) + "/attachments")
                .header("X-Atlassian-Token", "no-check")
                .header("Content-Type", "multipart/form-data; boundary="
                        + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(content),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
        JsonElement result;
        try {
            result = JsonParser.parseString(send(request));
        } catch (com.google.gson.JsonParseException exception) {
            throw new JiraRequestException(
                    "Jira returned invalid attachment JSON.",
                    "INVALID_RESPONSE", null, true, false, exception);
        }
        if (!result.isJsonArray()) {
            throw new JiraRequestException(
                    "Jira returned an invalid attachment response.",
                    "INVALID_RESPONSE", null, true, false, null);
        }
        return result.getAsJsonArray();
    }

    public void deleteAttachment(String id) throws IOException {
        if (id == null || !id.matches("[0-9]+")) {
            throw new IOException("Attachment ID must be numeric.");
        }
        request("DELETE", "attachment/" + id, null);
    }

    public static String sha256(byte[] content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public JsonElement request(String method, String path, JsonObject data)
            throws IOException {
        return request(method, path, data, false);
    }

    public JsonElement agile(String method, String path, JsonObject data)
            throws IOException {
        return request(method, path, data, true);
    }

    private JsonElement request(String method, String path, JsonObject data,
            boolean agile) throws IOException {
        var builder = builder(agile ? "rest/agile/1.0/" : api, path);
        if (data == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
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
            throw new JiraRequestException("Jira returned invalid JSON.",
                    "INVALID_RESPONSE", null, !readOnly(method, path),
                    false, exception);
        }
    }

    private HttpRequest.Builder builder(String path) {
        return builder(api, path);
    }

    private HttpRequest.Builder builder(String prefix, String path) {
        return HttpRequest.newBuilder(base.resolve(prefix + path))
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
                var status = response.statusCode();
                boolean unknown = !readOnly(request.method(), request.uri().getPath())
                        && status >= 500;
                throw new JiraRequestException("Jira HTTP " + status
                        + " for " + request.method() + " "
                        + request.uri().getPath() + ".",
                        unknown ? "OUTCOME_UNKNOWN" : "HTTP_ERROR",
                        status, unknown, !unknown && (status == 429
                        || (readOnly(request.method(), request.uri().getPath())
                        && status >= 500)), null);
            }
            return response.body();
        } catch (JiraRequestException exception) {
            throw exception;
        } catch (IOException exception) {
            boolean read = readOnly(request.method(), request.uri().getPath());
            throw new JiraRequestException(
                    read ? "Jira request failed during transport."
                            : "Jira write outcome is unknown; inspect Jira before retrying.",
                    read ? "TRANSPORT_ERROR" : "OUTCOME_UNKNOWN",
                    null, !read, read, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            boolean read = readOnly(request.method(), request.uri().getPath());
            throw new JiraRequestException("Jira request interrupted.",
                    read ? "REQUEST_INTERRUPTED" : "OUTCOME_UNKNOWN",
                    null, !read, false, exception);
        }
    }

    private static boolean readOnly(String method, String path) {
        return method.equals("GET") || method.equals("HEAD")
                || (method.equals("POST")
                && (path.equals("search") || path.equals("search/jql")
                || path.endsWith("/search") || path.endsWith("/search/jql")));
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
