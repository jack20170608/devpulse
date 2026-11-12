package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.customFields;
import static io.github.devpulse.jira.cli.JiraCommand.load;
import static io.github.devpulse.jira.cli.JiraCommand.nonblank;
import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.setNamed;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.text;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.devpulse.jira.JiraMarkdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "edit", mixinStandardHelpOptions = true,
        description = "Edit selected issue fields, or update an exported file.")
public final class JiraIssueEditCommand extends JiraCommand.Action {
    @Mixin
    JiraCommand.BodyFormat bodyFormat = new JiraCommand.BodyFormat();
    @Mixin
    JiraCommand.TemplateFormat templateFormat = new JiraCommand.TemplateFormat();
    @Parameters(index = "0", paramLabel = "KEY")
    String key;
    @Option(names = {"-s", "--summary"}, paramLabel = "TEXT",
            description = "Replace the summary.")
    String summary;
    @Option(names = {"-t", "--type"}, paramLabel = "NAME",
            description = "Replace the issue type.")
    String type;
    @Option(names = {"-y", "--priority"}, paramLabel = "NAME",
            description = "Replace the priority.")
    String priority;
    @Option(names = {"-a", "--assignee"}, paramLabel = "USER",
            description = "Cloud accountId or DC username; "
                    + "me=current user, x=unassigned.")
    String assignee;
    @Option(names = {"-P", "--parent"}, paramLabel = "KEY",
            description = "Replace the parent issue key.")
    String parent;
    @Option(names = {"-l", "--label"}, paramLabel = "LABEL",
            description = "Add a label; use --label=-NAME to remove. Repeatable.")
    List<String> labels;
    @Option(names = {"-C", "--component"}, paramLabel = "NAME",
            description = "Add a component; prefix with - to remove. Repeatable.")
    List<String> components;
    @Option(names = "--fix-version", paramLabel = "NAME",
            description = "Add a fix version; prefix with - to remove. Repeatable.")
    List<String> fixVersions;
    @Option(names = "--affects-version", paramLabel = "NAME",
            description = "Add an affected version; prefix with - to remove. Repeatable.")
    List<String> affectsVersions;
    @Option(names = {"-b", "--body"}, paramLabel = "MARKDOWN",
            description = "Replace description; an empty string clears it.")
    String body;
    @Option(names = "--template", paramLabel = "FILE",
            description = "Replace description from UTF-8 file, or - for stdin; "
                    + "see --template-format for input format.")
    Path template;
    @Option(names = "--custom", paramLabel = "FIELD=HOCON",
            description = "Set customfield_ID to a HOCON value. Repeatable.")
    List<String> custom;
    @Option(names = "--file", paramLabel = "EXPORTED.conf",
            description = "Update an export for KEY using the existing "
                    + "change/concurrency checks; cannot be mixed with edits.")
    Path file;
    @Option(names = "--no-input",
            description = "Never prompt; at least one edit or --file is required.")
    boolean noInput;
    @Option(names = "--force",
            description = "Skip the updated-time check; may overwrite "
                    + "concurrent edits.")
    boolean force;

    @Override
    protected void execute() throws IOException {
        var format = templateFormat.resolve(template, bodyFormat);
        if (file != null) {
            if (hasEdits()) {
                throw wrong("--file cannot be combined with field edits.");
            }
            var data = load(file);
            if (!data.hasPath("key") || !data.getString("key").equals(key)) {
                throw wrong("Exported issue key does not match KEY.");
            }
            var update = new JiraCommand.Update();
            update.file = file;
            update.force = force;
            update.bodyFormat = bodyFormat;
            update.spec = spec;
            update.execute();
            machineData = update.machineData;
            return;
        }
        if (body != null && template != null) {
            throw wrong("Use either --body or --template, not both.");
        }
        var interactive = !hasEdits();
        if (interactive && (noInput || System.console() == null)) {
            throw wrong("Supply at least one edit option or --file; "
                    + "interactive editing requires a terminal.");
        }
        var fields = new JsonObject();
        var updates = new JsonObject();
        listUpdates(updates, "labels", labels, false);
        listUpdates(updates, "components", components, true);
        listUpdates(updates, "fixVersions", fixVersions, true);
        listUpdates(updates, "versions", affectsVersions, true);
        customFields(fields, custom);
        var description = description();
        prepareFields(fields);
        var jira = client();
        var current = object(jira.issue(key).get("fields"));
        if (interactive) {
            summary = prompt("Summary (Enter to keep)");
            priority = prompt("Priority name (Enter to keep)");
            assignee = prompt("Assignee (me/x/user; Enter to keep)");
            body = prompt("Description (single line; Enter to keep)");
            prepareFields(fields);
            description = body;
        }
        if (description != null) {
            fields.add("description", description.isEmpty() ? JsonNull.INSTANCE
                    : JiraMarkdown.toJira(description,
                            jira.apiVersion() == 3, format));
        }
        if (assignee != null) {
            var user = nonblank(assignee, "--assignee");
            if (user.equals("default")) {
                throw wrong("issue edit requires an explicit assignee, "
                        + "me, or x; default assignment is not supported.");
            }
            if (user.equals("x")) {
                fields.add("assignee", JsonNull.INSTANCE);
            } else {
                var identifier = jira.cloud() ? "accountId" : "name";
                if (user.equals("me")) {
                    user = string(jira.myself(), identifier);
                }
                setNamed(fields, "assignee", identifier, user);
            }
        }
        for (var name : List.copyOf(fields.keySet())) {
            if (unchanged(name, fields.get(name), current.get(name))) {
                fields.remove(name);
            }
        }
        if (fields.isEmpty() && updates.isEmpty()) {
            changed(key, false);
            print("No changes to upload.");
            return;
        }
        var editable = object(jira.editMeta(key).get("fields"));
        for (var name : fields.keySet()) {
            requireOperation(editable, name, "set");
        }
        for (var entry : updates.entrySet()) {
            for (var value : entry.getValue().getAsJsonArray()) {
                for (var operation : object(value).keySet()) {
                    requireOperation(editable, entry.getKey(), operation);
                }
            }
        }
        if (!force) {
            var remote = object(jira.issue(key).get("fields"));
            if (!string(current, "updated").equals(string(remote, "updated"))) {
                throw wrong("Issue changed while preparing edits; retry, "
                        + "or explicitly use --force to override.");
            }
        }
        jira.edit(key, fields, updates);
        changed(key, true);
        print("Updated " + key + ".");
    }

    private boolean hasEdits() {
        return summary != null || type != null || priority != null
                || assignee != null || parent != null || labels != null
                || components != null || fixVersions != null
                || affectsVersions != null || body != null
                || template != null || custom != null;
    }

    private void prepareFields(JsonObject fields) throws IOException {
        if (summary != null) {
            fields.addProperty("summary", nonblank(summary, "--summary"));
        }
        setNamed(fields, "issuetype", "name", type);
        setNamed(fields, "priority", "name", priority);
        setNamed(fields, "parent", "key", parent);
    }

    private String description() throws IOException {
        if (body != null) {
            return body;
        }
        if (template == null) {
            return null;
        }
        return template.toString().equals("-")
                ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                : text(template);
    }

    private String prompt(String label) throws IOException {
        var console = System.console();
        if (console == null) {
            throw wrong("Interactive editing requires a terminal.");
        }
        spec.commandLine().getErr().print(label + ": ");
        spec.commandLine().getErr().flush();
        var answer = console.readLine();
        if (answer == null) {
            throw wrong("Input ended; issue was not updated.");
        }
        return answer.isBlank() ? null : answer;
    }

    private static void listUpdates(JsonObject updates, String field,
            List<String> values, boolean named) throws IOException {
        if (values == null) {
            return;
        }
        var operations = new LinkedHashMap<String, Boolean>();
        for (var value : values) {
            var remove = value.startsWith("-");
            var name = nonblank(remove ? value.substring(1) : value, field);
            var previous = operations.putIfAbsent(name, remove);
            if (previous != null && previous != remove) {
                throw wrong("Cannot both add and remove the same "
                        + field + " value.");
            }
        }
        var array = new JsonArray();
        for (var entry : operations.entrySet()) {
            JsonElement value;
            if (named) {
                var item = new JsonObject();
                item.addProperty("name", entry.getKey());
                value = item;
            } else {
                value = new JsonPrimitive(entry.getKey());
            }
            var operation = new JsonObject();
            operation.add(entry.getValue() ? "remove" : "add", value);
            array.add(operation);
        }
        updates.add(field, array);
    }

    private static boolean unchanged(String field, JsonElement proposed,
            JsonElement current) {
        if (proposed.equals(current)) {
            return true;
        }
        if (!List.of("issuetype", "priority", "parent", "assignee")
                .contains(field) || current == null || !current.isJsonObject()
                || !proposed.isJsonObject()) {
            return false;
        }
        var named = proposed.getAsJsonObject();
        return named.size() == 1 && named.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(
                        current.getAsJsonObject().get(entry.getKey())));
    }

    private static void requireOperation(JsonObject editable, String field,
            String operation) throws IOException {
        var metadata = editable.get(field);
        if (metadata == null || !metadata.isJsonObject()) {
            throw wrong("Field " + field + " is not reported as editable.");
        }
        var allowed = metadata.getAsJsonObject().get("operations");
        if (allowed == null || !allowed.isJsonArray()
                || !allowed.getAsJsonArray().contains(
                        new JsonPrimitive(operation))) {
            throw wrong("Jira does not allow " + operation
                    + " on field " + field + ".");
        }
    }
}
