package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.customFields;
import static io.github.devpulse.jira.cli.JiraCommand.nonblank;
import static io.github.devpulse.jira.cli.JiraCommand.setNamed;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.text;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraClient;
import io.github.devpulse.jira.JiraMarkdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "create", mixinStandardHelpOptions = true,
        description = "Create an issue using prompts, flags, or "
                + "Markdown issue documents.")
public class JiraCreateCommand extends JiraCommand.Action {
    @Mixin
    JiraCommand.BodyFormat bodyFormat = new JiraCommand.BodyFormat();
    @Mixin
    JiraCommand.TemplateFormat templateFormat = new JiraCommand.TemplateFormat();
    @Option(names = "--file", paramLabel = "ISSUE.md",
            description = "YAML metadata and description in one Markdown file; "
                    + "explicit flags override document fields.")
    Path file;
    @Option(names = {"-p", "--project"}, paramLabel = "KEY",
            description = "Project key; defaults to template or connection configuration.")
    String project;
    @Option(names = {"-t", "--type"}, paramLabel = "NAME",
            description = "Issue type name, e.g. Task or Bug.")
    String type;
    @Option(names = {"-s", "--summary"}, paramLabel = "TEXT",
            description = "Issue summary.")
    String summary;
    @Option(names = {"-y", "--priority"}, paramLabel = "NAME",
            description = "Priority name.")
    String priority;
    @Option(names = {"-a", "--assignee"}, paramLabel = "USER",
            description = "Cloud accountId or DC username; "
                    + "me=self, x=unassigned, default=project default.")
    String assignee;
    @Option(names = {"-l", "--label"}, paramLabel = "LABEL",
            description = "Repeat for multiple labels; replaces "
                    + "template labels.")
    List<String> labels;
    @Option(names = {"-C", "--component"}, paramLabel = "NAME",
            description = "Repeat for multiple component names.")
    List<String> components;
    @Option(names = "--fix-version", paramLabel = "NAME",
            description = "Repeat for multiple fix versions.")
    List<String> fixVersions;
    @Option(names = "--affects-version", paramLabel = "NAME",
            description = "Repeat for multiple affected versions.")
    List<String> affectsVersions;
    @Option(names = {"-P", "--parent"}, paramLabel = "KEY",
            description = "Parent issue key; subject to Jira hierarchy.")
    String parent;
    @Option(names = "--epic", paramLabel = "KEY",
            description = "Optional Epic key; blank skips association. "
                    + "Cannot be combined with --parent.")
    String epic;
    @Option(names = "--story-point", paramLabel = "NUMBER",
            description = "Story Points; overrides document metadata.")
    BigDecimal storyPoint;
    @Option(names = {"-b", "--body"}, paramLabel = "MARKDOWN",
            description = "Description text; conflicts with --template.")
    String body;
    @Option(names = "--template", paramLabel = "FILE",
            description = "UTF-8 description file, or - for stdin; "
                    + "overrides the issue document body. "
                    + "See --template-format for input format.")
    Path template;
    @Option(names = "--custom", paramLabel = "FIELD=HOCON",
            description = "Repeat to set customfield_ID using a "
                    + "HOCON value; overrides template custom fields.")
    List<String> custom;
    @Option(names = "--no-input",
            description = "Never prompt; missing required fields fail.")
    boolean noInput;

    @Override
    protected void execute() throws IOException {
        if (body != null && template != null) {
            throw wrong("Use either --body or --template, not both.");
        }
        var format = templateFormat.resolve(template, bodyFormat);
        var epicSupplied = epic != null;
        if (epic != null && epic.isBlank()) {
            epic = null;
        }
        if (epic != null) {
            JiraCatalogCommands.issueId(epic);
            if (parent != null) {
                throw wrong("Use either --epic or --parent, not both.");
            }
        }
        var document = file == null ? null : JiraIssueDocument.read(file);
        var issue = new JsonObject();
        setNamed(issue, "project", "key", project);
        setNamed(issue, "issuetype", "name", type);
        if (summary != null) {
            issue.addProperty("summary", nonblank(summary, "--summary"));
        }
        setNamed(issue, "priority", "name", priority);
        setNamed(issue, "parent", "key", parent);
        setList(issue, "labels", labels, false);
        setList(issue, "components", components, true);
        setList(issue, "fixVersions", fixVersions, true);
        setList(issue, "versions", affectsVersions, true);
        customFields(issue, custom);
        var jira = client();
        var overridden = new HashSet<>(issue.keySet());
        if (assignee != null) {
            overridden.add("assignee");
        }
        if (body != null || template != null) {
            overridden.add("description");
        }
        if (epicSupplied || parent != null) {
            overridden.add("epic");
        }
        if (epic != null) {
            overridden.add("parent");
        }
        if (storyPoint != null) {
            overridden.add("story-point");
        }
        if (document != null) {
            var metadata = document.needsMetadata(jira.cloud(), overridden)
                    ? jira.request("GET", "field", null).getAsJsonArray() : new JsonArray();
            var imported = document.fields(true, jira.cloud(), overridden, metadata);
            for (var entry : issue.entrySet()) {
                imported.add(entry.getKey(), entry.getValue());
            }
            issue = imported;
        }
        if (!issue.has("project")) {
            setNamed(issue, "project", "key", configuration().project());
        }
        requiredField(issue, "project", "key", "Project key", "--project");
        requiredField(issue, "issuetype", "name", "Issue type", "--type");
        requiredField(issue, "summary", null, "Summary", "--summary");

        if (!epicSupplied && !noInput && System.console() != null
                && file == null && !issue.has("parent")
                && !"Epic".equalsIgnoreCase(type)) {
            var answer = prompt("Epic key (optional; Enter to skip)");
            if (!answer.isBlank()) {
                epic = JiraCatalogCommands.issueId(answer);
            }
        }
        var description = description(document);
        if (epic != null) {
            setEpic(issue, jira);
        }
        if (storyPoint != null) {
            var field = JiraAgileCommands.storyPointsField(
                    jira.request("GET", "field", null).getAsJsonArray());
            if (field == null) {
                throw wrong("Jira field metadata does not expose Story Points.");
            }
            issue.addProperty(field, storyPoint);
        }
        if (description != null) {
            issue.add("description", JiraMarkdown.toJira(description,
                    jira.apiVersion() == 3, document != null && body == null && template == null
                            && bodyFormat.format == null ? document.bodyFormat() : format));
        }
        if (assignee != null) {
            var user = nonblank(assignee, "--assignee");
            if (user.equals("x")) {
                issue.add("assignee", com.google.gson.JsonNull.INSTANCE);
            } else if (user.equals("default")) {
                issue.remove("assignee");
            } else {
                var identifier = jira.cloud() ? "accountId" : "name";
                if (user.equals("me")) {
                    user = string(jira.myself(), identifier);
                }
                setNamed(issue, "assignee", identifier, user);
            }
        }
        var created = jira.create(issue);
        machineData = created;
        print("Created " + string(created, "key"));
    }

    private void setEpic(JsonObject issue, JiraClient jira) throws IOException {
        if (jira.cloud()) {
            if (issue.has("parent")) {
                throw wrong("--epic conflicts with the template parent field; "
                        + "Cloud uses parent for Epic association.");
            }
            setNamed(issue, "parent", "key", epic);
        } else {
            var field = JiraAgileCommands.epicField(jira, "Epic Link",
                    "com.pyxis.greenhopper.jira:gh-epic-link");
            if (field == null) {
                throw wrong("Jira field metadata does not expose "
                        + "an Epic Link field.");
            }
            issue.addProperty(field, epic);
        }
    }

    private String description(JiraIssueDocument document) throws IOException {
        if (body != null) {
            return body;
        }
        if (template != null) {
            return template.toString().equals("-")
                    ? new String(System.in.readAllBytes(),
                            StandardCharsets.UTF_8)
                    : text(template);
        }
        if (document != null) {
            return document.body().isBlank() ? null : document.body();
        }
        if (!machine() && System.console() == null) {
            var input = new String(System.in.readAllBytes(),
                    StandardCharsets.UTF_8);
            return input.isEmpty() ? null : input;
        }
        if (!noInput && System.console() != null) {
            return prompt("Description (optional; use --template "
                    + "for multiline text)");
        }
        return null;
    }

    private void requiredField(JsonObject issue, String field,
            String member, String label, String flag) throws IOException {
        var value = issue.get(field);
        if (value == null || value.isJsonNull()) {
            if (noInput || System.console() == null) {
                throw wrong("Missing required issue field: " + field
                        + "; supply " + flag + " or --file"
                        + " (interactive prompts require a terminal).");
            }
            var answer = nonblank(prompt(label), flag);
            if (member == null) {
                issue.addProperty(field, answer);
            } else {
                setNamed(issue, field, member, answer);
            }
            return;
        }
        if (member != null) {
            if (!value.isJsonObject()) {
                throw wrong("Issue field " + field
                        + " must contain a nonblank id or " + member + ".");
            }
            var named = value.getAsJsonObject();
            if (!nonblankString(named.get("id"))
                    && !nonblankString(named.get(member))) {
                throw wrong("Issue field " + field
                        + " must contain a nonblank id or " + member + ".");
            }
        } else if (!nonblankString(value)) {
            throw wrong("Issue field " + field
                    + " must be a nonblank string.");
        }
    }

    private String prompt(String label) throws IOException {
        var console = System.console();
        if (console == null) {
            throw wrong("Interactive prompts require a terminal.");
        }
        spec.commandLine().getErr().print(label + ": ");
        spec.commandLine().getErr().flush();
        var answer = console.readLine();
        if (answer == null) {
            throw wrong("Input ended; issue was not created.");
        }
        return answer;
    }

    private static boolean nonblankString(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isBlank();
    }

    private static void setList(JsonObject issue, String field,
            List<String> values, boolean named) throws IOException {
        if (values == null) {
            return;
        }
        var array = new JsonArray();
        for (var value : values) {
            nonblank(value, field);
            if (named) {
                var item = new JsonObject();
                item.addProperty("name", value);
                array.add(item);
            } else {
                array.add(value);
            }
        }
        issue.add(field, array);
    }
}
