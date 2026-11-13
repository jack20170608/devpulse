package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.devpulse.jira.JiraMarkdown;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.MappingEndEvent;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.SequenceEndEvent;
import org.snakeyaml.engine.v2.events.SequenceStartEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.JsonSchema;

record JiraIssueDocument(JsonObject metadata, String body) {
    private static final int SIZE_LIMIT = 1024 * 1024;
    private static final Pattern DELIMITER = Pattern.compile("(?m)^---[ \\t]*(?:\\r?\\n|$)");
    private static final Set<String> READ_ONLY = Set.of(
            "version", "key", "updated", "created", "reporter", "status",
            "mode", "apiVersion", "bodyFormat", "descriptionSha256", "originalFields");
    private static final Set<String> STANDARD = Set.of(
            "project", "summary", "issuetype", "priority", "parent", "assignee",
            "labels", "components", "fixVersions", "versions", "environment",
            "timetracking", "resolution", "epic", "story-point", "description");

    static void requireMarkdown(Path file) throws IOException {
        var name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".md") && !name.endsWith(".markdown")) {
            throw wrong("Issue files must be Markdown with YAML frontmatter; HOCON issue files are no longer supported.");
        }
    }

    static JiraIssueDocument read(Path file) throws IOException {
        requireMarkdown(file);
        if (Files.size(file) > SIZE_LIMIT) {
            throw wrong("Issue document must not exceed 1 MiB.");
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    static JiraIssueDocument parse(String source) throws IOException {
        if (source.startsWith("\uFEFF")) {
            source = source.substring(1);
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > SIZE_LIMIT) {
            throw wrong("Issue document must not exceed 1 MiB.");
        }
        var delimiter = DELIMITER.matcher(source);
        if (!delimiter.find() || delimiter.start() != 0) {
            throw wrong("Issue document must start with YAML frontmatter wrapped in ---.");
        }
        var start = delimiter.end();
        if (!delimiter.find()) {
            throw wrong("Issue YAML frontmatter needs a closing --- delimiter.");
        }
        var yaml = source.substring(start, delimiter.start());
        var body = source.substring(delimiter.end());
        if (body.startsWith("\r\n")) {
            body = body.substring(2);
        } else if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        var settings = LoadSettings.builder().setSchema(new JsonSchema())
                .setCodePointLimit(SIZE_LIMIT).setMaxAliasesForCollections(0).build();
        try {
            int depth = 0;
            for (var event : new Parse(settings).parseString(yaml)) {
                if (event instanceof AliasEvent) {
                    throw wrong("Issue YAML aliases are not supported.");
                }
                if (event instanceof MappingStartEvent || event instanceof SequenceStartEvent) {
                    if (++depth > 64) {
                        throw wrong("Issue YAML nesting exceeds 64 levels.");
                    }
                } else if (event instanceof MappingEndEvent || event instanceof SequenceEndEvent) {
                    depth--;
                }
            }
            var node = new Compose(settings).composeString(yaml)
                    .orElseThrow(() -> wrong("Issue YAML metadata must be a mapping."));
            var value = convert(node);
            if (!value.isJsonObject()) {
                throw wrong("Issue YAML metadata must be a mapping.");
            }
            var document = new JiraIssueDocument(value.getAsJsonObject(), body);
            if (document.metadata.has("version")
                    && !document.metadata.get("version").equals(new JsonPrimitive(1))) {
                throw wrong("Unsupported issue document version.");
            }
            document.originalFields();
            return document;
        } catch (YamlEngineException | NumberFormatException exception) {
            throw wrong("Invalid issue YAML metadata.");
        }
    }

    private static JsonElement convert(Node node) throws IOException {
        if (node.getAnchor().isPresent()) {
            throw wrong("Issue YAML anchors are not supported.");
        }
        if (node instanceof MappingNode mapping && node.getTag().equals(Tag.MAP)) {
            var result = new JsonObject();
            for (var tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)
                        || !key.getTag().equals(Tag.STR) || key.getAnchor().isPresent()
                        || key.getValue().equals("<<")) {
                    throw wrong("Issue YAML keys must be strings; merge keys are not supported.");
                }
                if (result.has(key.getValue())) {
                    throw wrong("Duplicate issue YAML key.");
                }
                result.add(key.getValue(), convert(tuple.getValueNode()));
            }
            return result;
        }
        if (node instanceof SequenceNode sequence && node.getTag().equals(Tag.SEQ)) {
            var result = new JsonArray();
            for (var value : sequence.getValue()) {
                result.add(convert(value));
            }
            return result;
        }
        if (node instanceof ScalarNode scalar) {
            if (node.getTag().equals(Tag.STR)) {
                return new JsonPrimitive(scalar.getValue());
            }
            if (node.getTag().equals(Tag.NULL)) {
                return JsonNull.INSTANCE;
            }
            if (node.getTag().equals(Tag.BOOL)) {
                if (!scalar.getValue().equals("true") && !scalar.getValue().equals("false")) {
                    throw wrong("YAML booleans must be true or false.");
                }
                return new JsonPrimitive(Boolean.parseBoolean(scalar.getValue()));
            }
            if (node.getTag().equals(Tag.INT) || node.getTag().equals(Tag.FLOAT)) {
                return new JsonPrimitive(new BigDecimal(scalar.getValue()));
            }
        }
        throw wrong("Unsupported issue YAML tag.");
    }

    static String render(JsonObject metadata, String body) throws IOException {
        var output = new StringBuilder("---\n");
        for (var entry : metadata.entrySet()) {
            var key = entry.getKey();
            output.append(key.matches("[A-Za-z_][A-Za-z0-9_-]*")
                    ? key : new JsonPrimitive(key).toString());
            output.append(": ").append(entry.getValue().toString()
                    .replace("\u0085", "\\u0085")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029")).append('\n');
        }
        var result = output.append("---\n\n").append(body).toString();
        var roundTrip = parse(result);
        if (!roundTrip.metadata.equals(metadata) || !roundTrip.body.equals(body)) {
            throw wrong("Issue document cannot be represented losslessly in YAML.");
        }
        return result;
    }

    String string(String name) throws IOException {
        var value = metadata.get(name);
        if (empty(value)) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw wrong("Issue metadata " + name + " must be a string.");
        }
        return value.getAsString();
    }

    JsonObject originalFields() throws IOException {
        var value = metadata.get("originalFields");
        if (value == null) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw wrong("originalFields must contain the unchanged export baseline.");
        }
        return value.getAsJsonObject();
    }

    void requireBaseline() throws IOException {
        if (originalFields() == null || string("key") == null || string("updated") == null
                || string("descriptionSha256") == null || string("mode") == null
                || !metadata.has("apiVersion")) {
            throw wrong("Use jira export to create an update document.");
        }
    }

    JiraMarkdown.Format bodyFormat() throws IOException {
        var value = string("bodyFormat");
        try {
            return value == null ? JiraMarkdown.Format.markdown : JiraMarkdown.Format.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw wrong("bodyFormat must be markdown or wiki.");
        }
    }

    String changedBody(JiraMarkdown.Format override, String currentMarkdown) throws IOException {
        if (body.isBlank()) {
            return null;
        }
        var format = override == null ? bodyFormat() : override;
        var originalHash = string("descriptionSha256");
        var unchanged = originalHash != null
                ? JiraCommand.hash(body).equals(originalHash) && format == bodyFormat()
                : body.stripTrailing().equals(currentMarkdown.stripTrailing())
                        && format == JiraMarkdown.Format.markdown;
        return unchanged ? null : body;
    }

    void checkTarget(String key, String updated, boolean cloud, int apiVersion, boolean force)
            throws IOException {
        if (string("key") != null && !string("key").equals(key)) {
            throw wrong("Issue document key does not match KEY.");
        }
        if (string("mode") != null && !string("mode").equals(cloud ? "cloud" : "dc")) {
            throw wrong("Exported Jira mode does not match configuration.");
        }
        if (metadata.has("apiVersion") && (!metadata.get("apiVersion").isJsonPrimitive()
                || !metadata.getAsJsonPrimitive("apiVersion").isNumber()
                || metadata.get("apiVersion").getAsBigDecimal().compareTo(BigDecimal.valueOf(apiVersion)) != 0)) {
            throw wrong("Exported Jira API version does not match configuration.");
        }
        if (!force && string("updated") != null && !string("updated").equals(updated)) {
            throw wrong("Issue changed since export; re-export, or explicitly use --force.");
        }
    }

    boolean needsMetadata(boolean cloud, Set<String> overridden) {
        return (!overridden.contains("story-point") && metadata.has("story-point")
                && !emptyString(metadata.get("story-point")))
                || (!cloud && !overridden.contains("epic") && metadata.has("epic")
                && !emptyString(metadata.get("epic")));
    }

    JsonObject fields(boolean creating, boolean cloud, Set<String> overridden, JsonArray jiraFields)
            throws IOException {
        var result = new JsonObject();
        var baseline = originalFields();
        var original = creating ? null : baseline;
        for (var entry : metadata.entrySet()) {
            var name = entry.getKey();
            var value = entry.getValue();
            if (READ_ONLY.contains(name) || (!creating && name.equals("project"))
                    || overridden.contains(name) || emptyString(value)
                    || (original != null && value.equals(original.get(name)))) {
                continue;
            }
            if (!STANDARD.contains(name) && !name.matches("customfield_[0-9]+")
                    && (baseline == null || !baseline.has(name))) {
                throw wrong("Unknown issue metadata field: " + name + ".");
            }
            if (List.of("summary", "issuetype", "project").contains(name) && value.isJsonNull()) {
                throw wrong("Cannot clear required issue field " + name + ".");
            }
            if (name.equals("description")) {
                if (!value.isJsonNull() || !body.isBlank()) {
                    throw wrong("Description belongs in the Markdown body; use description: null with an empty body to clear.");
                }
                result.add("description", JsonNull.INSTANCE);
            } else if (name.equals("epic")) {
                var field = cloud ? "parent" : JiraAgileCommands.epicField(
                        jiraFields, "Epic Link", "com.pyxis.greenhopper.jira:gh-epic-link");
                if (field == null) {
                    throw wrong("Jira field metadata does not expose an Epic Link field.");
                }
                if (overridden.contains(field)) {
                    continue;
                }
                if (!field.equals("parent") && metadata.has(field)
                        && !emptyString(metadata.get(field))) {
                    throw wrong("Use either epic or its customfield_ID, not both.");
                }
                if (result.has("parent") || (!empty(metadata.get("parent")) && !overridden.contains("parent"))) {
                    throw wrong("Use either epic or parent, not both.");
                }
                result.add(field, cloud ? named(value, "key", name) : scalar(value, name));
            } else if (name.equals("story-point")) {
                var field = JiraAgileCommands.storyPointsField(jiraFields);
                if (field == null) {
                    throw wrong("Jira field metadata does not expose Story Points.");
                }
                if (overridden.contains(field)) {
                    continue;
                }
                if (metadata.has(field) && !emptyString(metadata.get(field))) {
                    throw wrong("Use either story-point or its customfield_ID, not both.");
                }
                if (!value.isJsonNull() && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())) {
                    throw wrong("story-point must be a number or null.");
                }
                result.add(field, value);
            } else if (List.of("project", "parent").contains(name)) {
                result.add(name, named(value, "key", name));
            } else if (List.of("issuetype", "priority", "resolution").contains(name)) {
                result.add(name, named(value, "name", name));
            } else if (name.equals("assignee")) {
                result.add(name, named(value, cloud ? "accountId" : "name", name));
            } else if (List.of("components", "fixVersions", "versions").contains(name)) {
                if (value.isJsonNull()) {
                    result.add(name, new JsonArray());
                } else if (value.isJsonArray()) {
                    var items = new JsonArray();
                    for (var item : value.getAsJsonArray()) {
                        items.add(named(item, "name", name));
                    }
                    result.add(name, items);
                } else {
                    throw wrong(name + " must be an array or null.");
                }
            } else {
                if (List.of("summary", "environment").contains(name)) {
                    scalar(value, name);
                }
                if (name.equals("labels") && value.isJsonNull()) {
                    result.add(name, new JsonArray());
                    continue;
                }
                if (name.equals("labels")) {
                    if (!value.isJsonArray()) {
                        throw wrong("labels must be an array or null.");
                    }
                    for (var label : value.getAsJsonArray()) {
                        scalar(label, name);
                        if (label.isJsonNull()) {
                            throw wrong("labels must contain nonblank strings.");
                        }
                    }
                }
                result.add(name, value);
            }
        }
        return result;
    }

    static JsonObject exportFields(JsonObject source, boolean cloud) {
        var result = new JsonObject();
        for (var entry : source.entrySet()) {
            var name = entry.getKey();
            var value = entry.getValue();
            var member = switch (name) {
                case "project", "parent" -> "key";
                case "issuetype", "priority", "resolution" -> "name";
                case "assignee" -> cloud ? "accountId" : "name";
                default -> null;
            };
            if (member != null && value.isJsonObject() && value.getAsJsonObject().has(member)) {
                value = value.getAsJsonObject().get(member);
            } else if (List.of("components", "fixVersions", "versions").contains(name) && value.isJsonArray()) {
                var values = new JsonArray();
                for (var item : value.getAsJsonArray()) {
                    values.add(item.isJsonObject() && item.getAsJsonObject().has("name")
                            ? item.getAsJsonObject().get("name") : item);
                }
                value = values;
            }
            result.add(name, value.deepCopy());
        }
        return result;
    }

    private static JsonElement named(JsonElement value, String member, String name) throws IOException {
        if (value.isJsonNull()) {
            return value.deepCopy();
        }
        if (value.isJsonObject()) {
            var named = value.getAsJsonObject();
            var identifier = named.has(member) ? named.get(member) : named.get("id");
            if (identifier == null || identifier.isJsonNull()) {
                throw wrong(name + " requires " + member + " or id, not a display name.");
            }
            scalar(identifier, name);
            return value.deepCopy();
        }
        var result = new JsonObject();
        result.add(member, scalar(value, name));
        return result;
    }

    private static JsonElement scalar(JsonElement value, String name) throws IOException {
        if (!value.isJsonNull() && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank())) {
            throw wrong(name + " must be a nonblank string or null.");
        }
        return value;
    }

    private static boolean emptyString(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() && value.getAsString().isBlank();
    }

    private static boolean empty(JsonElement value) {
        return value == null || value.isJsonNull() || emptyString(value);
    }
}
