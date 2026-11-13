package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.nonblank;
import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraClient;
import io.github.devpulse.jira.JiraDescriptionMarkdown;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Non-fullscreen issue operations sharing the configured Jira connection. */
public final class JiraIssueOperations {
    private JiraIssueOperations() {
    }

    @Command(name = "view", mixinStandardHelpOptions = true,
            description = "Print issue details and recent comments without a pager.")
    public static final class View extends JiraCommand.Action {
        private static final List<String> MARKDOWN_FIELDS = List.of(
                "summary", "key", "issuetype", "status", "priority",
                "assignee", "reporter", "created", "updated", "epic", "story-point");

        @Parameters(index = "0", paramLabel = "KEY")
        String key;
        @Option(names = "--comments", defaultValue = "1", paramLabel = "N",
                description = "Recent comments to fetch, newest first (0-1000).")
        int comments;
        @Option(names = {"--raw", "--json"},
                description = "Print JSON, with the requested recent comments.")
        boolean raw;
        @Option(names = "--plain", description = "Use the previous plain-text description display.")
        boolean plain;
        enum DescriptionFormat { markdown, raw }
        @Option(names = "--description-format", paramLabel = "markdown|raw",
                description = "Description display (default: markdown). "
                        + "Raw preserves Wiki text for v2 or ADF JSON for v3.")
        DescriptionFormat descriptionFormat;
        enum OutputFormat { text, markdown }
        @Option(names = "--format", defaultValue = "text", paramLabel = "text|markdown",
                description = "Output format (default: text). Markdown prints YAML "
                        + "frontmatter and description only, without links or comments.")
        OutputFormat format;
        @Option(names = "--fields", split = ",", paramLabel = "FIELD,...",
                description = "Ordered Markdown frontmatter fields: summary,key,issuetype,"
                        + "status,priority,assignee,reporter,created,updated,epic,story-point. "
                        + "Default: all. Requires --format markdown.")
        List<String> selectedFields;

        @Override
        protected void execute() throws IOException {
            key = issueKey(key);
            if (comments < 0 || comments > 1000) {
                throw wrong("--comments must be between 0 and 1000.");
            }
            if (plain && descriptionFormat != null) {
                throw wrong("Use either --plain or --description-format, not both.");
            }
            var outputFields = markdownFields();
            var jira = client();
            var issue = jira.issue(key).deepCopy();
            var fields = object(issue.get("fields"));
            if (format == OutputFormat.markdown) {
                var document = markdownDocument(jira, issue, fields, outputFields);
                if (machine()) {
                    var result = new JsonObject();
                    result.addProperty("key", label(issue, "key"));
                    result.addProperty("markdown", document);
                    machineData = result;
                } else {
                    print(document);
                }
                return;
            }
            var recent = new JsonArray();
            var total = 0;
            while (recent.size() < comments) {
                var start = recent.size();
                var page = object(jira.request("GET", "issue/" + encode(key)
                        + "/comment?orderBy=-created&startAt=" + start
                        + "&maxResults=" + Math.min(100, comments - start), null));
                if (page.has("startAt") && integer(page, "startAt") != start) {
                    throw wrong("Jira returned an unexpected comment page.");
                }
                var values = array(page.get("comments"));
                total = integer(page, "total");
                if (values.isEmpty()) {
                    if (start < total) {
                        throw wrong("Jira returned an incomplete comment page.");
                    }
                    break;
                }
                for (var value : values) {
                    if (recent.size() == comments) {
                        break;
                    }
                    recent.add(object(value).deepCopy());
                }
                if (recent.size() >= total) {
                    break;
                }
            }
            var commentPage = new JsonObject();
            commentPage.addProperty("total", total);
            commentPage.addProperty("orderBy", "-created");
            commentPage.add("comments", recent);
            fields.add("comment", commentPage);
            if (raw) {
                print(issue.toString());
                return;
            }
            print(safe(label(issue, "key") + "  " + label(fields, "summary")));
            for (var field : List.of("issuetype", "status", "priority",
                    "assignee", "reporter", "created", "updated")) {
                print(field + ": " + safe(display(fields.get(field))));
            }
            var description = fields.get("description");
            String rendered;
            if (plain) {
                rendered = printable(description);
            } else if (descriptionFormat == DescriptionFormat.raw) {
                rendered = description == null || description.isJsonNull() ? ""
                        : description.isJsonPrimitive()
                                ? description.getAsString() : description.toString();
            } else {
                rendered = JiraDescriptionMarkdown.render(description, jira.apiVersion() == 3);
            }
            print("\nDescription:\n" + (descriptionFormat == DescriptionFormat.raw
                    ? rendered : safe(rendered)));
            print("\nLinked issues:");
            var links = fields.get("issuelinks");
            if (links != null && links.isJsonArray()) {
                for (var value : links.getAsJsonArray()) {
                    var link = object(value);
                    var type = object(link.get("type"));
                    for (var direction : List.of("inward", "outward")) {
                        var other = link.get(direction + "Issue");
                        if (other != null && other.isJsonObject()) {
                            var linked = other.getAsJsonObject();
                            print(safe(label(type, direction) + " "
                                    + label(linked, "key") + " "
                                    + display(linked.get("fields"))));
                        }
                    }
                }
            }
            if (!recent.isEmpty()) {
                print("\nRecent comments (newest first):");
                for (var value : recent) {
                    var comment = object(value);
                    print(safe(display(comment.get("author")) + "  "
                            + label(comment, "created")));
                    print(printable(comment.get("body")));
                    print("");
                }
            }
        }

        private List<String> markdownFields() throws IOException {
            if (format != OutputFormat.markdown) {
                if (selectedFields != null) {
                    throw wrong("--fields requires --format markdown.");
                }
                return List.of();
            }
            var parsed = spec.commandLine().getParseResult();
            if (parsed.hasMatchedOption("--raw") || parsed.hasMatchedOption("--json")
                    || plain || descriptionFormat == DescriptionFormat.raw
                    || (parsed.hasMatchedOption("--comments") && comments != 0)) {
                throw wrong("--format markdown cannot be combined with --raw/--json, "
                        + "--plain, --description-format raw, or nonzero --comments.");
            }
            if (selectedFields == null) {
                return MARKDOWN_FIELDS;
            }
            var names = new LinkedHashSet<String>();
            for (var field : selectedFields) {
                var name = field.strip();
                if (!MARKDOWN_FIELDS.contains(name) || !names.add(name)) {
                    throw wrong("--fields contains an unknown, empty, or duplicate field.");
                }
            }
            if (names.isEmpty()) {
                throw wrong("--fields must select at least one field.");
            }
            return List.copyOf(names);
        }

        private String markdownDocument(JiraClient jira, JsonObject issue,
                JsonObject fields, List<String> names) throws IOException {
            var metadata = names.contains("story-point")
                    || (names.contains("epic") && !jira.cloud())
                    ? array(jira.request("GET", "field", null)) : new JsonArray();
            var epicField = names.contains("epic") && !jira.cloud()
                    ? JiraAgileCommands.epicField(metadata, "Epic Link",
                            "com.pyxis.greenhopper.jira:gh-epic-link") : null;
            var pointsField = names.contains("story-point") ? storyPointsField(metadata) : null;
            var document = new StringBuilder("---\n");
            for (var name : names) {
                var value = switch (name) {
                    case "key" -> issue.get("key");
                    case "epic" -> epicValue(fields, epicField);
                    case "story-point" -> pointsField == null ? null : fields.get(pointsField);
                    default -> fields.get(name);
                };
                document.append(name).append(": ");
                if (name.equals("story-point") && value != null && !value.isJsonNull()) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        throw wrong("Jira returned a nonnumeric Story Points value.");
                    }
                    document.append(value.getAsBigDecimal().toPlainString());
                } else {
                    var text = value == null || value.isJsonNull() ? "" : display(value);
                    document.append(new com.google.gson.JsonPrimitive(text).toString()
                            .replace("\u0085", "\\u0085")
                            .replace("\u2028", "\\u2028")
                            .replace("\u2029", "\\u2029"));
                }
                document.append('\n');
            }
            return document.append("---\n\n")
                    .append(safe(JiraDescriptionMarkdown.render(
                            fields.get("description"), jira.apiVersion() == 3))).toString();
        }

        private static String storyPointsField(JsonArray metadata) throws IOException {
            var matches = new LinkedHashSet<String>();
            for (var value : metadata) {
                var field = object(value);
                var id = label(field, "id");
                var name = label(field, "name");
                if (id.matches("customfield_[0-9]+")
                        && (name.equalsIgnoreCase("Story Points")
                                || name.equalsIgnoreCase("Story point estimate"))) {
                    matches.add(id);
                }
            }
            if (matches.size() > 1) {
                throw wrong("Jira field metadata has ambiguous Story Points fields.");
            }
            return matches.isEmpty() ? null : matches.iterator().next();
        }

        private static JsonElement epicValue(JsonObject fields, String epicField)
                throws IOException {
            if (epicField != null) {
                var value = fields.get(epicField);
                if (value != null && !value.isJsonNull()) {
                    return value;
                }
            }
            var parent = fields.get("parent");
            if (parent == null || !parent.isJsonObject()) {
                return null;
            }
            var parentFields = parent.getAsJsonObject().get("fields");
            if (parentFields == null || !parentFields.isJsonObject()) {
                return null;
            }
            var type = parentFields.getAsJsonObject().get("issuetype");
            if (type == null || !type.isJsonObject()) {
                return null;
            }
            var issueType = type.getAsJsonObject();
            var level = issueType.get("hierarchyLevel");
            boolean epic = level != null && !level.isJsonNull()
                    ? level.isJsonPrimitive() && level.getAsJsonPrimitive().isNumber()
                            && level.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0
                    : "Epic".equalsIgnoreCase(label(issueType, "name"));
            return epic ? parent.getAsJsonObject().get("key") : null;
        }
    }

    @Command(name = "link", mixinStandardHelpOptions = true,
            subcommands = Link.Remote.class,
            description = "Link issues using a configured link type or direction.")
    public static final class Link extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
        String key;
        @Parameters(index = "1", arity = "0..1", paramLabel = "OTHER")
        String other;
        @Parameters(index = "2", arity = "0..1", paramLabel = "TYPE")
        String type;
        @Option(names = "--no-input", description = "Fail instead of prompting.")
        boolean noInput;

        @Override
        protected void execute() throws IOException {
            key = issueKey(required(this, key, "Issue key", noInput));
            other = issueKey(required(this, other, "Other issue key", noInput));
            different(key, other);
            var jira = client();
            var types = array(object(jira.request("GET", "issueLinkType", null))
                    .get("issueLinkTypes"));
            if (type == null && !noInput && System.console() != null) {
                for (var value : types) {
                    var item = object(value);
                    print(safe(label(item, "id") + ": " + label(item, "name")
                            + " [" + label(item, "outward") + " / "
                            + label(item, "inward") + "]"));
                }
            }
            type = required(this, type, "Link type name, ID, or direction", noInput);
            var matches = new LinkedHashMap<String, LinkType>();
            for (var value : types) {
                var item = object(value);
                var id = numeric(string(item, "id"));
                if (matches(item, "id", type) || matches(item, "name", type)
                        || matches(item, "outward", type)) {
                    matches.put(id + ":out", new LinkType(id, false));
                }
                if (matches(item, "inward", type)
                        && !matches(item, "outward", type)
                        && !matches(item, "name", type)
                        && !matches(item, "id", type)) {
                    matches.put(id + ":in", new LinkType(id, true));
                }
            }
            if (matches.size() != 1) {
                throw wrong("Link type is unknown or ambiguous; use an exact "
                        + "configured type ID, name, or unique direction.");
            }
            var selected = matches.values().iterator().next();
            var body = new JsonObject();
            body.add("type", reference("id", selected.id()));
            body.add("inwardIssue", reference("key", selected.reverse() ? other : key));
            body.add("outwardIssue", reference("key", selected.reverse() ? key : other));
            jira.request("POST", "issueLink", body);
            print("Linked " + key + " and " + other + ".");
        }

        @Command(name = "remote", mixinStandardHelpOptions = true,
                description = "Add an HTTP(S) web link to an issue.")
        public static final class Remote extends JiraCommand.Action {
            @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
            String key;
            @Parameters(index = "1", arity = "0..1", paramLabel = "URL")
            String url;
            @Parameters(index = "2", arity = "0..1", paramLabel = "TITLE")
            String title;
            @Option(names = "--no-input", description = "Fail instead of prompting.")
            boolean noInput;

            @Override
            protected void execute() throws IOException {
                key = issueKey(required(this, key, "Issue key", noInput));
                url = required(this, url, "Web URL", noInput);
                title = required(this, title, "Link title", noInput);
                try {
                    var uri = new URI(url);
                    if (!Set.of("http", "https").contains(
                            uri.getScheme() == null ? "" : uri.getScheme()
                                    .toLowerCase(java.util.Locale.ROOT))
                            || uri.getHost() == null || uri.getUserInfo() != null
                            || uri.getPort() < -1 || uri.getPort() > 65535) {
                        throw wrong("URL must be an absolute HTTP(S) URL "
                                + "without embedded credentials.");
                    }
                } catch (URISyntaxException exception) {
                    throw wrong("Invalid remote link URL.");
                }
                var link = new JsonObject();
                link.addProperty("url", url);
                link.addProperty("title", title);
                var body = new JsonObject();
                body.add("object", link);
                client().request("POST", "issue/" + encode(key) + "/remotelink", body);
                print("Added remote link to " + key + ".");
            }
        }
    }

    @Command(name = "unlink", mixinStandardHelpOptions = true,
            description = "Remove all issue links between exactly two issues.")
    public static final class Unlink extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
        String key;
        @Parameters(index = "1", arity = "0..1", paramLabel = "OTHER")
        String other;
        @Option(names = "--no-input", description = "Fail instead of prompting.")
        boolean noInput;

        @Override
        protected void execute() throws IOException {
            key = issueKey(required(this, key, "Issue key", noInput));
            other = issueKey(required(this, other, "Other issue key", noInput));
            different(key, other);
            var jira = client();
            var first = jira.issue(key);
            var second = jira.issue(other);
            if (string(first, "id").equals(string(second, "id"))) {
                throw wrong("Choose two different issues.");
            }
            var links = array(object(first.get("fields")).get("issuelinks"));
            var ids = new LinkedHashSet<String>();
            for (var value : links) {
                var link = object(value);
                for (var direction : List.of("inwardIssue", "outwardIssue")) {
                    var endpoint = link.get(direction);
                    if (endpoint != null && endpoint.isJsonObject()) {
                        var target = endpoint.getAsJsonObject();
                        var sameId = matches(target, "id", string(second, "id"));
                        var sameKey = matches(target, "key", string(second, "key"));
                        if (target.has("id") && target.has("key")
                                && sameId != sameKey) {
                            throw wrong("Jira returned conflicting issue link endpoints.");
                        }
                        if (target.has("id") ? sameId : sameKey) {
                            ids.add(numeric(string(link, "id")));
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                throw wrong("No issue links connect the specified issues.");
            }
            for (var id : ids) {
                jira.request("DELETE", "issueLink/" + id, null);
            }
            print("Removed " + ids.size() + " link(s) between "
                    + key + " and " + other + ".");
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Delete an issue directly; --cascade also deletes subtasks.")
    public static final class Delete extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
        String key;
        @Option(names = "--cascade", description = "Delete the issue's subtasks too.")
        boolean cascade;
        @Option(names = "--no-input", description = "Fail instead of prompting.")
        boolean noInput;

        @Override
        protected void execute() throws IOException {
            key = issueKey(required(this, key, "Issue key to delete", noInput));
            client().request("DELETE", "issue/" + encode(key)
                    + "?deleteSubtasks=" + cascade, null);
            print("Deleted " + key + ".");
        }
    }

    @Command(name = "clone", mixinStandardHelpOptions = true,
            description = "Clone createable issue fields, without comments or attachments.")
    public static final class Clone extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
        String key;
        @Option(names = {"-s", "--summary"}, paramLabel = "TEXT")
        String summary;
        @Option(names = {"-y", "--priority"}, paramLabel = "NAME")
        String priority;
        @Option(names = {"-a", "--assignee"}, paramLabel = "USER",
                description = "Cloud accountId or DC username; me=self, x=unassigned, "
                        + "default=project default.")
        String assignee;
        @Option(names = {"-l", "--label"}, paramLabel = "LABEL",
                description = "Repeat to replace labels.")
        List<String> labels;
        @Option(names = {"-C", "--component"}, paramLabel = "NAME",
                description = "Repeat to replace components.")
        List<String> components;
        @Option(names = "--fix-version", paramLabel = "NAME",
                description = "Repeat to replace fix versions.")
        List<String> fixVersions;
        @Option(names = {"-H", "--replace"}, paramLabel = "FIND:REPLACEMENT",
                description = "Case-sensitive replacement in summary and description; repeatable.")
        List<String> replacements;
        @Option(names = "--no-input", description = "Fail instead of prompting.")
        boolean noInput;

        @Override
        protected void execute() throws IOException {
            key = issueKey(required(this, key, "Issue key to clone", noInput));
            var changes = replacementRules(replacements);
            if (summary != null) {
                checked(summary, "--summary");
            }
            var jira = client();
            var source = object(jira.issue(key).get("fields"));
            var project = object(source.get("project"));
            var type = object(source.get("issuetype"));
            var metadata = createMetadata(jira, numeric(string(project, "id")),
                    numeric(string(type, "id")));
            if (source.has("security") && !source.get("security").isJsonNull()) {
                requireCreateable(metadata, "security");
            }
            var fields = new JsonObject();
            fields.add("project", reference("id", string(project, "id")));
            fields.add("issuetype", reference("id", string(type, "id")));
            var skipped = new ArrayList<String>();
            for (var entry : metadata.entrySet()) {
                var name = entry.getKey();
                if (!copyable(name) || !canSet(entry.getValue())
                        || !source.has(name) || source.get(name).isJsonNull()) {
                    continue;
                }
                var value = normalized(name, source.get(name), entry.getValue(), jira);
                if (value == null) {
                    if (name.equals("security")) {
                        throw wrong("Cannot safely preserve the source issue security level.");
                    }
                    skipped.add(name);
                } else {
                    fields.add(name, value);
                }
            }
            if (summary != null) {
                requireCreateable(metadata, "summary");
                fields.addProperty("summary", summary);
            }
            if (fields.has("summary")) {
                fields.addProperty("summary", replace(
                        string(fields, "summary"), changes));
                checked(string(fields, "summary"), "Cloned summary");
            }
            if (fields.has("description")) {
                fields.add("description", replaceDescription(
                        fields.get("description"), changes));
            }
            if (priority != null) {
                fields.add("priority", namedOverride(metadata, "priority", priority));
            }
            overrideList(fields, metadata, "labels", labels, false);
            overrideList(fields, metadata, "components", components, true);
            overrideList(fields, metadata, "fixVersions", fixVersions, true);
            if (assignee != null) {
                requireCreateable(metadata, "assignee");
                var user = checked(assignee, "--assignee");
                if (user.equals("x")) {
                    fields.add("assignee", JsonNull.INSTANCE);
                } else if (user.equals("default")) {
                    fields.remove("assignee");
                } else {
                    var identifier = jira.cloud() ? "accountId" : "name";
                    fields.add("assignee", reference(identifier, user.equals("me")
                            ? checked(string(jira.myself(), identifier), "Current user")
                            : user));
                }
            }
            var reporter = metadata.get("reporter");
            if (reporter != null && bool(reporter, "required")
                    && !bool(reporter, "hasDefaultValue") && canSet(reporter)) {
                var identifier = jira.cloud() ? "accountId" : "name";
                fields.add("reporter", reference(identifier,
                        checked(string(jira.myself(), identifier), "Current user")));
            }
            for (var entry : metadata.entrySet()) {
                var meta = entry.getValue();
                if (bool(meta, "required") && !bool(meta, "hasDefaultValue")
                        && (!fields.has(entry.getKey())
                                || fields.get(entry.getKey()).isJsonNull())) {
                    throw wrong("Cannot safely clone a required create field; "
                            + "use issue create with an explicit template.");
                }
            }
            if (!fields.has("summary")) {
                throw wrong("Source has no createable summary.");
            }
            if (!skipped.isEmpty()) {
                spec.commandLine().getErr().println("jira: omitted "
                        + skipped.size() + " unsupported field value(s).");
            }
            print("Created " + string(jira.create(fields), "key")
                    + " from " + key + ".");
        }
    }

    private record LinkType(String id, boolean reverse) {
    }

    private record Replacement(String find, String replacement) {
    }

    private static String required(JiraCommand.Action action, String value,
            String label, boolean noInput) throws IOException {
        if (value != null) {
            return checked(value, label);
        }
        if (noInput || System.console() == null) {
            throw wrong("Missing required argument: " + label
                    + "; supply it explicitly (prompts require a terminal).");
        }
        action.spec.commandLine().getErr().print(label + ": ");
        action.spec.commandLine().getErr().flush();
        var answer = System.console().readLine();
        if (answer == null) {
            throw wrong("Input ended; no change was made.");
        }
        return checked(answer.strip(), label);
    }

    private static String checked(String value, String label) throws IOException {
        nonblank(value, label);
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw wrong(label + " must not contain control characters.");
        }
        return value;
    }

    private static String issueKey(String value) throws IOException {
        if (value == null || !value.matches("(?:[A-Za-z][A-Za-z0-9_]*-[1-9][0-9]*|[1-9][0-9]*)")) {
            throw wrong("KEY must be a Jira issue key or numeric issue ID.");
        }
        return value;
    }

    private static String numeric(String value) throws IOException {
        if (value == null || !value.matches("[0-9]+")) {
            throw wrong("Jira returned an invalid numeric identifier.");
        }
        return value;
    }

    private static void different(String first, String second) throws IOException {
        if (first.equalsIgnoreCase(second)) {
            throw wrong("Choose two different issues.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonArray array(JsonElement value) throws IOException {
        if (value == null || !value.isJsonArray()) {
            throw wrong("Jira returned an unexpected JSON array.");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject value, String field) throws IOException {
        var item = value.get(field);
        if (item == null || !item.isJsonPrimitive()) {
            throw wrong("Jira returned a missing or invalid response field.");
        }
        return item.getAsString();
    }

    private static int integer(JsonObject value, String field) throws IOException {
        try {
            var result = Integer.parseInt(string(value, field));
            if (result >= 0) {
                return result;
            }
        } catch (NumberFormatException ignored) {
            // Report only a safe shape error, not the server's value.
        }
        throw wrong("Jira returned invalid pagination.");
    }

    private static JsonObject reference(String field, String value) {
        var result = new JsonObject();
        result.addProperty(field, value);
        return result;
    }

    private static String label(JsonObject value, String field) {
        var item = value.get(field);
        return item != null && item.isJsonPrimitive()
                ? item.getAsString() : "";
    }

    private static boolean matches(JsonObject value, String field, String query) {
        return !label(value, field).isEmpty()
                && label(value, field).equalsIgnoreCase(query);
    }

    private static String display(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "-";
        }
        if (value.isJsonObject()) {
            var data = value.getAsJsonObject();
            for (var field : List.of("displayName", "name", "summary", "key", "id")) {
                if (!label(data, field).isEmpty()) {
                    return label(data, field);
                }
            }
            return "-";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String safe(String value) {
        var result = new StringBuilder();
        value.codePoints().forEach(character -> {
            if (!Character.isISOControl(character) || character == '\n'
                    || character == '\t') {
                result.appendCodePoint(character);
            } else {
                result.append(' ');
            }
        });
        return result.toString();
    }

    private static String printable(JsonElement value) {
        var result = new StringBuilder();
        appendText(value, result, 0);
        return safe(result.toString()).strip();
    }

    private static void appendText(JsonElement value, StringBuilder output, int depth) {
        if (value == null || value.isJsonNull() || depth > 100) {
            return;
        }
        if (value.isJsonPrimitive()) {
            output.append(value.getAsString());
        } else if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) {
                appendText(item, output, depth + 1);
            }
        } else {
            var node = value.getAsJsonObject();
            var type = label(node, "type");
            if (type.equals("hardBreak")) {
                output.append('\n');
                return;
            }
            if (node.has("text") && node.get("text").isJsonPrimitive()) {
                output.append(node.get("text").getAsString());
            }
            if (node.has("content")) {
                appendText(node.get("content"), output, depth + 1);
            } else if (node.has("attrs") && node.get("attrs").isJsonObject()) {
                var attrs = node.getAsJsonObject("attrs");
                for (var field : List.of("text", "shortName", "alt", "url")) {
                    if (!label(attrs, field).isEmpty()) {
                        output.append(label(attrs, field));
                        break;
                    }
                }
            }
            if (Set.of("paragraph", "heading", "listItem", "codeBlock",
                    "blockquote", "tableRow", "rule").contains(type)) {
                output.append('\n');
            } else if (type.equals("tableCell") || type.equals("tableHeader")) {
                output.append('\t');
            }
        }
    }

    private static Map<String, JsonObject> createMetadata(JiraClient jira,
            String project, String type) throws IOException {
        var result = new LinkedHashMap<String, JsonObject>();
        if (jira.cloud()) {
            var start = 0;
            while (start < 5000) {
                var page = object(jira.request("GET", "issue/createmeta/"
                        + project + "/issuetypes/" + type + "?startAt="
                        + start + "&maxResults=100", null));
                var values = array(page.has("fields")
                        ? page.get("fields") : page.get("values"));
                for (var value : values) {
                    var field = object(value);
                    var id = string(field, "fieldId");
                    if (result.putIfAbsent(id, field) != null) {
                        throw wrong("Jira returned duplicate create metadata.");
                    }
                }
                start += values.size();
                if (bool(page, "isLast")
                        || page.has("total") && start >= integer(page, "total")) {
                    return result;
                }
                if (values.isEmpty()) {
                    throw wrong("Jira returned incomplete create metadata.");
                }
            }
            throw wrong("Too many create fields; use issue create explicitly.");
        }
        var data = object(jira.request("GET", "issue/createmeta?projectIds="
                + project + "&issuetypeIds=" + type
                + "&expand=projects.issuetypes.fields", null));
        for (var projectValue : array(data.get("projects"))) {
            var projectMeta = object(projectValue);
            if (!matches(projectMeta, "id", project)) {
                continue;
            }
            for (var typeValue : array(projectMeta.get("issuetypes"))) {
                var typeMeta = object(typeValue);
                if (matches(typeMeta, "id", type)) {
                    if (!result.isEmpty()) {
                        throw wrong("Jira returned ambiguous create metadata.");
                    }
                    for (var field : object(typeMeta.get("fields")).entrySet()) {
                        result.put(field.getKey(), object(field.getValue()));
                    }
                }
            }
        }
        if (result.isEmpty()) {
            throw wrong("No create metadata is available for this project and type.");
        }
        return result;
    }

    private static boolean bool(JsonObject data, String field) {
        var value = data.get(field);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static boolean copyable(String field) {
        return field.matches("customfield_[0-9]+")
                || Set.of("summary", "description", "priority", "assignee",
                        "labels", "components", "fixVersions", "versions",
                        "parent", "environment", "duedate", "security").contains(field);
    }

    private static boolean canSet(JsonObject metadata) throws IOException {
        if (!metadata.has("operations")) {
            return true;
        }
        for (var operation : array(metadata.get("operations"))) {
            if (operation.isJsonPrimitive()
                    && operation.getAsString().equals("set")) {
                return true;
            }
        }
        return false;
    }

    private static void requireCreateable(Map<String, JsonObject> metadata,
            String field) throws IOException {
        if (!metadata.containsKey(field) || !canSet(metadata.get(field))) {
            throw wrong("Requested field is not createable for this project and type.");
        }
    }

    private static JsonElement normalized(String field, JsonElement value,
            JsonObject metadata, JiraClient jira) throws IOException {
        if (field.equals("description") || field.equals("environment")) {
            return value.isJsonPrimitive() || value.isJsonObject()
                    && label(value.getAsJsonObject(), "type").equals("doc")
                    ? value.deepCopy() : null;
        }
        if (field.equals("assignee")) {
            return identity(value, jira.cloud() ? "accountId" : "name");
        }
        if (Set.of("parent", "priority", "security").contains(field)) {
            return identity(value, "id");
        }
        if (Set.of("components", "fixVersions", "versions").contains(field)) {
            return normalizeArray(value, "id");
        }
        if (!field.startsWith("customfield_")) {
            if (field.equals("labels") && value.isJsonArray()) {
                for (var item : value.getAsJsonArray()) {
                    if (!item.isJsonPrimitive()
                            || !item.getAsJsonPrimitive().isString()) {
                        return null;
                    }
                }
                return value.deepCopy();
            }
            if (value.isJsonPrimitive()) {
                return value.deepCopy();
            }
            return null;
        }
        var schemaValue = metadata.get("schema");
        if (schemaValue == null || !schemaValue.isJsonObject()) {
            return null;
        }
        var schema = schemaValue.getAsJsonObject();
        var type = label(schema, "type");
        if (type.equals("array")) {
            var items = label(schema, "items");
            if (items.equals("user")) {
                return normalizeArray(value, jira.cloud() ? "accountId" : "name");
            }
            if (Set.of("option", "version", "component", "group").contains(items)) {
                return normalizeArray(value, items.equals("group") ? "name" : "id");
            }
            if (value.isJsonArray() && Set.of("string", "number").contains(items)) {
                var copy = new JsonArray();
                for (var item : value.getAsJsonArray()) {
                    if (!item.isJsonPrimitive()) {
                        return null;
                    }
                    copy.add(item.deepCopy());
                }
                return copy;
            }
            return null;
        }
        if (type.equals("user")) {
            return identity(value, jira.cloud() ? "accountId" : "name");
        }
        if (Set.of("option", "option-with-child", "version", "component", "project", "issuetype",
                "priority", "group").contains(type)) {
            var copy = identity(value, type.equals("group") ? "name" : "id");
            var childValue = copy == null ? null : value.getAsJsonObject().get("child");
            if (copy != null && childValue != null && !childValue.isJsonNull()) {
                var child = identity(childValue, "id");
                if (child == null) {
                    return null;
                }
                copy.add("child", child);
            }
            return copy;
        }
        return value.isJsonPrimitive()
                && Set.of("string", "number", "date", "datetime", "boolean")
                        .contains(type) ? value.deepCopy() : null;
    }

    private static JsonObject identity(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        var identifier = label(value.getAsJsonObject(), field);
        return identifier.isBlank() ? null : reference(field, identifier);
    }

    private static JsonArray normalizeArray(JsonElement value, String identifier) {
        if (!value.isJsonArray()) {
            return null;
        }
        var result = new JsonArray();
        for (var item : value.getAsJsonArray()) {
            var named = identity(item, identifier);
            if (named == null) {
                return null;
            }
            result.add(named);
        }
        return result;
    }

    private static JsonObject namedOverride(Map<String, JsonObject> metadata,
            String field, String input) throws IOException {
        requireCreateable(metadata, field);
        checked(input, "Field override");
        var choices = metadata.get(field).get("allowedValues");
        if (choices == null || !choices.isJsonArray()) {
            return reference("name", input);
        }
        var matches = new ArrayList<JsonObject>();
        for (var choice : choices.getAsJsonArray()) {
            var candidate = object(choice);
            if (label(candidate, "name").equals(input)
                    || label(candidate, "id").equals(input)) {
                var id = identity(candidate, "id");
                if (id != null && !matches.contains(id)) {
                    matches.add(id);
                }
            }
        }
        if (matches.size() != 1) {
            throw wrong("Field override is unknown or ambiguous in create metadata.");
        }
        return matches.getFirst();
    }

    private static void overrideList(JsonObject fields,
            Map<String, JsonObject> metadata, String field,
            List<String> values, boolean named) throws IOException {
        if (values == null) {
            return;
        }
        requireCreateable(metadata, field);
        var result = new JsonArray();
        for (var value : values) {
            checked(value, "Field override");
            if (named) {
                result.add(namedOverride(metadata, field, value));
            } else {
                result.add(value);
            }
        }
        fields.add(field, result);
    }

    private static List<Replacement> replacementRules(List<String> values)
            throws IOException {
        var result = new ArrayList<Replacement>();
        if (values != null) {
            for (var value : values) {
                var separator = value.indexOf(':');
                if (separator <= 0) {
                    throw wrong("--replace requires nonempty FIND:REPLACEMENT.");
                }
                result.add(new Replacement(value.substring(0, separator),
                        value.substring(separator + 1)));
            }
        }
        return result;
    }

    private static String replace(String text, List<Replacement> replacements) {
        for (var rule : replacements) {
            text = text.replace(rule.find(), rule.replacement());
        }
        return text;
    }

    private static JsonElement replaceDescription(JsonElement value,
            List<Replacement> replacements) {
        var copy = value.deepCopy();
        if (copy.isJsonPrimitive() && copy.getAsJsonPrimitive().isString()) {
            return new com.google.gson.JsonPrimitive(
                    replace(copy.getAsString(), replacements));
        }
        replaceTextNodes(copy, replacements, 0);
        return copy;
    }

    private static void replaceTextNodes(JsonElement value,
            List<Replacement> replacements, int depth) {
        if (depth > 100) {
            return;
        }
        if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) {
                replaceTextNodes(item, replacements, depth + 1);
            }
        } else if (value.isJsonObject()) {
            var node = value.getAsJsonObject();
            var text = node.get("text");
            if (text != null && text.isJsonPrimitive()
                    && text.getAsJsonPrimitive().isString()) {
                node.addProperty("text", replace(text.getAsString(), replacements));
            }
            if (node.has("content")) {
                replaceTextNodes(node.get("content"), replacements, depth + 1);
            }
        }
    }
}
