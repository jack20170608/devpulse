package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCatalogCommands.array;
import static io.github.devpulse.jira.cli.JiraCatalogCommands.encode;
import static io.github.devpulse.jira.cli.JiraCatalogCommands.integer;
import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraConfiguration;
import io.github.devpulse.jira.JiraRequestException;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

final class JiraSkillEnvironment {
    private static final int PAGE_SIZE = 100;
    private static final int PAGE_LIMIT = 1000;

    @FunctionalInterface
    interface Request {
        JsonElement send(String method, String path, JsonObject body) throws IOException;
    }

    private final JiraConfiguration configuration;
    private final Request api;
    private final Request agile;

    JiraSkillEnvironment(JiraConfiguration configuration, Request api, Request agile) {
        this.configuration = configuration;
        this.api = api;
        this.agile = agile;
    }

    JsonObject collect(String selectedProject) throws IOException {
        var project = object(api.send("GET",
                "project/" + encode(selectedProject) + "?expand=issueTypes", null));
        var key = JiraCatalogCommands.project(string(project, "key"), null);
        if (!key.equalsIgnoreCase(selectedProject)
                && !string(project, "id").equals(selectedProject)) {
            throw wrong("Jira returned a different project.");
        }
        var actor = object(api.send("GET", "myself", null));
        var identity = cloud() ? "accountId" : "name";
        JiraCommand.nonblank(string(actor, identity), "Current user");
        var snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", 1);
        snapshot.addProperty("capturedAt", Instant.now().toString());
        snapshot.addProperty("serverUrl", configuration.url());
        snapshot.addProperty("mode", configuration.mode());
        snapshot.addProperty("apiVersion", configuration.apiVersion());
        snapshot.add("project", select(project, "id", "key", "name"));
        snapshot.add("actor", select(actor, identity));

        var types = section();
        try {
            var source = project.get("issueTypes");
            if (source == null && cloud()) {
                source = api.send("GET", "issuetype/project?projectId="
                        + encode(string(project, "id")), null);
            }
            for (var value : array(source)) {
                var type = object(value);
                JiraCatalogCommands.numeric(string(type, "id"), "Issue type ID");
                items(types).add(select(type, "id", "name", "untranslatedName",
                        "subtask", "hierarchyLevel"));
            }
        } catch (IOException exception) {
            fail(types, exception);
        }
        snapshot.add("issueTypes", types);

        var epics = section();
        try {
            var ids = new JsonArray();
            for (var value : items(types)) {
                var type = object(value);
                if (epicType(type)) {
                    ids.add(string(type, "id"));
                }
            }
            if (!complete(types) || ids.isEmpty()) {
                limited(epics, complete(types)
                        ? "EPIC_TYPE_NOT_IDENTIFIED" : "ISSUE_TYPE_DISCOVERY_INCOMPLETE");
                epics.addProperty("status", "unavailable");
            } else {
                collectEpics(key, ids, epics);
            }
        } catch (IOException exception) {
            fail(epics, exception);
        }
        snapshot.add("epics", epics);

        var users = section();
        try {
            collectAssignees(key, users);
        } catch (IOException exception) {
            fail(users, exception);
        }
        snapshot.add("assignableUsers", users);

        var boards = section();
        try {
            collectPages(agile, "board?projectKeyOrId=" + encode(key),
                    boards, List.of("id", "name", "type"), null);
        } catch (IOException exception) {
            fail(boards, exception);
        }
        snapshot.add("boards", boards);
        snapshot.add("sprints", collectSprints(boards));
        snapshot.addProperty("finishedAt", Instant.now().toString());
        return snapshot;
    }

    private void collectEpics(String project, JsonArray typeIds, JsonObject section)
            throws IOException {
        var ids = new java.util.ArrayList<String>();
        typeIds.forEach(value -> ids.add(value.getAsString()));
        var jql = "project = \"" + project + "\" AND issuetype in ("
                + String.join(",", ids) + ") ORDER BY key ASC";
        var seen = new HashSet<String>();
        var tokens = new HashSet<String>();
        String token = null;
        int offset = 0;
        for (int pageNumber = 0; pageNumber < PAGE_LIMIT; pageNumber++) {
            var body = new JsonObject();
            body.addProperty("jql", jql);
            body.addProperty("maxResults", PAGE_SIZE);
            var fields = new JsonArray();
            fields.add("summary");
            fields.add("status");
            body.add("fields", fields);
            if (!cloud()) {
                body.addProperty("startAt", offset);
            } else if (token != null) {
                body.addProperty("nextPageToken", token);
            }
            var page = object(api.send("POST", cloud() ? "search/jql" : "search", body));
            var batch = array(page.get("issues"));
            for (var value : batch) {
                var issue = object(value);
                if (!seen.add(string(issue, "key"))) {
                    throw wrong("Epic pages overlap.");
                }
                var entry = select(issue, "id", "key");
                var issueFields = object(issue.get("fields"));
                entry.add("summary", issueFields.get("summary"));
                if (issueFields.has("status") && issueFields.get("status").isJsonObject()) {
                    entry.add("status", select(issueFields.getAsJsonObject("status"), "id", "name"));
                }
                items(section).add(entry);
            }
            if (cloud()) {
                var next = page.get("nextPageToken");
                if (page.has("isLast")) {
                    var last = page.get("isLast");
                    if (!last.isJsonPrimitive() || !last.getAsJsonPrimitive().isBoolean()) {
                        throw wrong("Invalid Cloud search pagination metadata.");
                    }
                    if (last.getAsBoolean()) {
                        return;
                    }
                }
                if (next == null || next.isJsonNull()) {
                    throw wrong("Cloud search continuation is missing.");
                }
                token = next.getAsString();
                if (token.isBlank() || !tokens.add(token)) {
                    throw wrong("Cloud search cursor did not advance.");
                }
            } else {
                offset = nextOffset(page, batch.size(), offset);
                if (offset < 0) {
                    return;
                }
            }
        }
        limited(section, "PAGE_LIMIT");
    }

    private void collectAssignees(String project, JsonObject section) throws IOException {
        var seen = new HashSet<String>();
        var identity = cloud() ? "accountId" : "name";
        int maximum = cloud() ? 10 : PAGE_LIMIT;
        for (int pageNumber = 0; pageNumber < maximum; pageNumber++) {
            int offset = pageNumber * PAGE_SIZE;
            var batch = array(api.send("GET", "user/assignable/search?project="
                    + encode(project) + "&startAt=" + offset + "&maxResults=" + PAGE_SIZE, null));
            for (var value : batch) {
                var user = object(value);
                var id = string(user, identity);
                if (!seen.add(id)) {
                    throw wrong("Assignable user pages overlap.");
                }
                items(section).add(select(user, identity, "displayName", "active"));
            }
            // Cloud filters a 1000-user window: even an empty page is not an end marker.
            if (!cloud() && batch.size() < PAGE_SIZE) {
                return;
            }
        }
        limited(section, cloud() ? "CLOUD_USER_SEARCH_LIMIT" : "PAGE_LIMIT");
    }

    private JsonObject collectSprints(JsonObject boards) throws IOException {
        var result = section();
        result.addProperty("support", "unknown");
        var perBoard = new JsonArray();
        result.add("boards", perBoard);
        if (!complete(boards)) {
            limited(result, "BOARD_DISCOVERY_INCOMPLETE");
        }
        boolean found = false;
        for (var value : items(boards)) {
            var board = object(value);
            if (!"scrum".equals(string(board, "type"))) {
                continue;
            }
            found = true;
            var id = JiraCatalogCommands.numeric(string(board, "id"), "Board ID");
            var sprints = section();
            sprints.addProperty("boardId", id);
            try {
                collectPages(agile, "board/" + id + "/sprint?", sprints,
                        List.of("id", "name", "state", "startDate", "endDate",
                                "completeDate", "originBoardId", "goal"), id);
                result.addProperty("support", "available");
            } catch (IOException exception) {
                fail(sprints, exception);
            }
            perBoard.add(sprints);
            items(sprints).forEach(item -> items(result).add(item.deepCopy()));
            if (!complete(sprints)) {
                limited(result, "SPRINT_DISCOVERY_INCOMPLETE");
            }
        }
        if (!found && complete(boards)) {
            result.addProperty("support", "no-visible-scrum-board");
        }
        return result;
    }

    private static void collectPages(Request request, String path, JsonObject section,
            List<String> fields, String board) throws IOException {
        int offset = 0;
        var seen = new HashSet<String>();
        for (int pageNumber = 0; pageNumber < PAGE_LIMIT; pageNumber++) {
            var page = object(request.send("GET", path + (path.endsWith("?") ? "" : "&")
                    + "startAt=" + offset + "&maxResults=" + PAGE_SIZE, null));
            var batch = array(page.get("values"));
            for (var value : batch) {
                var item = object(value);
                if (!seen.add(string(item, "id"))) {
                    throw wrong("Jira catalog pages overlap.");
                }
                var entry = select(item, fields.toArray(String[]::new));
                if (board != null) {
                    entry.addProperty("boardId", board);
                }
                items(section).add(entry);
            }
            offset = nextOffset(page, batch.size(), offset);
            if (offset < 0) {
                return;
            }
        }
        limited(section, "PAGE_LIMIT");
    }

    private static int nextOffset(JsonObject page, int count, int requested) throws IOException {
        if (integer(page, "startAt") != requested) {
            throw wrong("Jira returned an unexpected pagination offset.");
        }
        return JiraCatalogCommands.next(page, count);
    }

    private static boolean epicType(JsonObject type) {
        if (type.has("hierarchyLevel") && !type.get("hierarchyLevel").isJsonNull()
                && type.get("hierarchyLevel").getAsInt() == 1) {
            return true;
        }
        for (var property : List.of("name", "untranslatedName")) {
            var name = type.get(property);
            if (name != null && name.isJsonPrimitive()
                    && ("Epic".equalsIgnoreCase(name.getAsString())
                    || "史诗".equals(name.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    private boolean cloud() {
        return configuration.mode().equals("cloud");
    }

    static JsonObject select(JsonObject source, String... names) {
        var result = new JsonObject();
        for (var name : names) {
            if (source.has(name)) {
                result.add(name, source.get(name).deepCopy());
            }
        }
        return result;
    }

    private static JsonObject section() {
        var result = new JsonObject();
        result.addProperty("status", "complete");
        result.addProperty("complete", true);
        result.add("items", new JsonArray());
        return result;
    }

    private static JsonArray items(JsonObject section) {
        return section.getAsJsonArray("items");
    }

    private static boolean complete(JsonObject section) {
        return section.get("complete").getAsBoolean();
    }

    private static void limited(JsonObject section, String reason) {
        section.addProperty("status", "partial");
        section.addProperty("complete", false);
        section.addProperty("reason", reason);
    }

    private static void fail(JsonObject section, IOException exception) {
        section.addProperty("status", items(section).isEmpty() ? "unavailable" : "partial");
        section.addProperty("complete", false);
        section.addProperty("reason", "QUERY_FAILED");
        var error = new JsonObject();
        error.addProperty("code", exception instanceof JiraRequestException request
                ? request.code() : "INVALID_RESPONSE");
        if (exception instanceof JiraRequestException request && request.httpStatus() != null) {
            error.addProperty("httpStatus", request.httpStatus());
        }
        section.add("error", error);
    }
}
