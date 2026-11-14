package io.github.devpulse.jira.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValueFactory;
import io.github.devpulse.jira.JiraClient;
import io.github.devpulse.jira.JiraConfiguration;
import io.github.devpulse.jira.JiraMarkdown;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "jira", mixinStandardHelpOptions = true,
        versionProvider = JiraCommand.VersionProvider.class,
        description = "Manage Jira issues using HOCON and Markdown.",
        subcommands = {JiraCommand.Issue.class,
                JiraInitCommand.class,
                JiraCommand.Export.class,
                JiraCommand.Update.class, JiraCommand.Transitions.class,
                JiraSkillCommands.class,
                JiraCommand.Transition.class, JiraCommand.Comment.class,
                JiraCommand.CommentExport.class,
                JiraCommand.CommentUpdate.class,
                JiraIssueExtras.Me.class, JiraIssueExtras.Completion.class,
                JiraAgileCommands.Epic.class, JiraAgileCommands.Sprint.class,
                JiraCatalogCommands.Project.class, JiraCatalogCommands.Board.class,
                JiraCatalogCommands.Release.class, JiraCatalogCommands.Open.class,
                JiraCommand.Attach.class})
public final class JiraCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Option(names = {"-c", "--config"},
            scope = ScopeType.INHERIT, paramLabel = "FILE",
            description = "Jira connection HOCON file; otherwise read "
                    + "local_jira_config.conf in the current directory, "
                    + "then ~/.jira/local_jira_config.conf. "
                    + "Token comes from "
                    + "JIRA_API_TOKEN only.")
    private Path configFile;

    @Option(names = "--machine", scope = ScopeType.INHERIT,
            description = "Versioned JSON result; disable prompts and implicit stdin. "
                    + "Does not authorize or approve writes.")
    boolean machine;

    public static void main(String[] args) {
        var cli = new CommandLine(new JiraCommand())
                .setOut(new PrintWriter(System.out, true,
                        StandardCharsets.UTF_8))
                .setErr(new PrintWriter(System.err, true,
                        StandardCharsets.UTF_8));
        System.exit(JiraMachine.configure(cli, (JiraCommand) cli.getCommand())
                .execute(args));
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            var version = JiraCommand.class.getPackage()
                    .getImplementationVersion();
            return new String[]{"devpulse-jira "
                    + (version == null ? "development" : version)};
        }
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static String readBody(String body, InputStream input) throws IOException {
        return "-".equals(body) ? new String(input.readAllBytes(), StandardCharsets.UTF_8) : body;
    }

    static com.typesafe.config.Config load(Path file)
            throws IOException {
        var options = ConfigParseOptions.defaults()
                .setSyntax(ConfigSyntax.CONF).setAllowMissing(false)
                .setOriginDescription(file.toString())
                .setIncluder(new NoIncludes());
        try {
            var config = ConfigFactory.parseString(text(file), options)
                    .resolve(ConfigResolveOptions.noSystem());
            if (config.getInt("version") != 1) {
                throw new IOException("Unsupported Jira template version.");
            }
            return config;
        } catch (ConfigException exception) {
            throw new IOException("Invalid Jira template: "
                    + exception.getMessage(), exception);
        }
    }

    private static void write(Path file, String data) throws IOException {
        Files.writeString(file, data, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static String hash(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(
                    content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String render(Map<String, Object> values) {
        return ConfigValueFactory.fromMap(values).render(
                ConfigRenderOptions.defaults().setJson(false)
                        .setComments(false).setOriginComments(false)) + "\n";
    }

    static IOException wrong(String message) {
        return new IOException(message);
    }

    static String string(JsonObject object, String field)
            throws IOException {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw wrong("Jira response is missing " + field + ".");
        }
        return object.get(field).getAsString();
    }

    static void customFields(JsonObject issue, List<String> custom)
            throws IOException {
        if (custom == null) {
            return;
        }
        for (var entry : custom) {
            var separator = entry.indexOf('=');
            if (separator < 0 || !entry.substring(0, separator)
                    .matches("customfield_[0-9]+")) {
                throw wrong("Use --custom customfield_ID=HOCON_VALUE.");
            }
            try {
                var parsed = ConfigFactory.parseString(
                        "value = " + entry.substring(separator + 1),
                        ConfigParseOptions.defaults()
                                .setSyntax(ConfigSyntax.CONF)
                                .setIncluder(new NoIncludes()))
                        .resolve(ConfigResolveOptions.noSystem());
                if (parsed.root().size() != 1
                        || !parsed.root().containsKey("value")) {
                    throw wrong("Each --custom must contain one "
                            + "HOCON value.");
                }
                issue.add(entry.substring(0, separator),
                        JsonParser.parseString(parsed.root().get("value")
                                .render(ConfigRenderOptions.concise())));
            } catch (ConfigException exception) {
                throw wrong("Invalid HOCON value for --custom.");
            }
        }
    }

    static String nonblank(String value, String option) throws IOException {
        if (value.isBlank()) {
            throw wrong(option + " must not be blank.");
        }
        return value;
    }

    static void setNamed(JsonObject issue, String field,
            String member, String value) throws IOException {
        if (value != null) {
            var named = new JsonObject();
            named.addProperty(member, nonblank(value, field));
            issue.add(field, named);
        }
    }

    static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw wrong("Jira returned an unexpected JSON object.");
        }
        return value.getAsJsonObject();
    }

    private static void requireExportConfiguration(
            com.typesafe.config.Config data, JiraClient jira)
            throws IOException {
        var mode = data.getString("mode");
        if (!mode.equals(jira.cloud() ? "cloud" : "dc")) {
            throw wrong("Exported Jira mode does not match configuration.");
        }
        var version = data.hasPathOrNull("apiVersion")
                ? data.getNumber("apiVersion").doubleValue()
                : mode.equals("cloud") ? 3 : 2;
        if (version != jira.apiVersion()) {
            throw wrong("Exported Jira API version does not match "
                    + "configuration; re-export before updating.");
        }
    }

    static final class DescriptionFormat {
        @Option(names = {"--desc-format", "--body-format"}, paramLabel = "markdown|wiki",
                description = "Description format: ${COMPLETION-CANDIDATES}. "
                        + "Default: markdown, or the document/export body format. "
                        + "Metadata is unaffected. --body-format is a compatibility alias. "
                        + "Wiki requires REST API v2.")
        JiraMarkdown.Format format;

        JiraMarkdown.Format resolve(JiraIssueDocument document, boolean explicitBody) throws IOException {
            if (format != null) {
                return format;
            }
            return document != null && !explicitBody ? document.bodyFormat() : JiraMarkdown.Format.markdown;
        }
    }

    static final class BodyFormat {
        @Option(names = "--body-format", paramLabel = "markdown|wiki",
                description = "Body input format: ${COMPLETION-CANDIDATES}. "
                        + "Default: markdown, or the exported format "
                        + "when updating. Wiki requires REST API v2.")
        JiraMarkdown.Format format;

        JsonElement toJira(String body, JiraClient jira) throws IOException {
            return JiraMarkdown.toJira(body, jira.apiVersion() == 3,
                    format == null ? JiraMarkdown.Format.markdown : format);
        }

        JsonElement toJira(String body, JiraClient jira,
                com.typesafe.config.Config metadata) throws IOException {
            if (format != null || !metadata.hasPath("bodyFormat")) {
                return toJira(body, jira);
            }
            try {
                return JiraMarkdown.toJira(body, jira.apiVersion() == 3,
                        JiraMarkdown.Format.valueOf(
                                metadata.getString("bodyFormat")));
            } catch (IllegalArgumentException exception) {
                throw wrong("Exported bodyFormat must be markdown or wiki.");
            }
        }

        boolean changesFormat(com.typesafe.config.Config metadata) {
            return format != null && !format.name().equals(
                    metadata.hasPath("bodyFormat")
                            ? metadata.getString("bodyFormat") : "markdown");
        }
    }

    abstract static class Action implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        private JiraConfiguration configuration;
        JsonElement machineData;

        protected abstract void execute() throws IOException;

        boolean machine() {
            return ((JiraCommand) spec.root().userObject()).machine;
        }

        void changed(String key, boolean changed) {
            var result = new JsonObject();
            result.addProperty("key", key);
            result.addProperty("changed", changed);
            machineData = result;
        }

        Path explicitConfigFile() {
            return ((JiraCommand) spec.root().userObject()).configFile;
        }

        JiraConfiguration configuration() throws IOException {
            if (configuration == null) {
                configuration = JiraConfiguration.load(
                        JiraConfiguration.find(explicitConfigFile()));
            }
            return configuration;
        }

        JiraClient client() throws IOException {
            return new JiraClient(configuration(),
                    System.getenv("JIRA_API_TOKEN"));
        }

        @Override
        public final Integer call() throws Exception {
            if (machine()) {
                execute();
                return 0;
            }
            try {
                execute();
                return 0;
            } catch (IOException | ConfigException
                    | com.google.gson.JsonParseException exception) {
                // Never print server bodies, tokens, or issue descriptions.
                spec.commandLine().getErr().println("jira: "
                        + exception.getMessage());
                return 1;
            } catch (IllegalStateException exception) {
                spec.commandLine().getErr().println(
                        "jira: unexpected Jira response or template shape.");
                return 1;
            }
        }

        void print(String message) {
            spec.commandLine().getOut().println(message);
        }
    }

    @Command(name = "template", mixinStandardHelpOptions = true,
            description = "Create a single Markdown issue "
            + "template without contacting Jira.")
    static final class IssueTemplate extends Action {
        @Option(names = "--file", required = true, paramLabel = "ISSUE.md")
        Path file;

        @Override
        protected void execute() throws IOException {
            JiraIssueDocument.requireMarkdown(file);
            write(file, """
                    ---
                    project: "DEMO"
                    issuetype: "Task"
                    summary: "Replace this summary"
                    priority: ""
                    assignee: ""
                    epic: ""
                    story-point: ""
                    labels: []
                    ---

                    请替换此描述。 / Replace this description.
                    """);
            print("Created " + file + ".");
        }
    }

    @Command(name = "issue", mixinStandardHelpOptions = true,
            description = "Manage Jira issues.",
            subcommands = {JiraCreateCommand.class, JiraIssueListCommand.class,
                    JiraIssueEditCommand.class, JiraIssueAssignCommand.class,
                    JiraIssueMoveCommand.class,
                    JiraIssueExtras.Comment.class, JiraIssueExtras.Worklog.class,
                    JiraIssueOperations.View.class, JiraIssueOperations.Link.class,
                    JiraIssueOperations.Unlink.class, JiraIssueOperations.Clone.class,
                    JiraIssueOperations.Delete.class,
                    JiraIssueExtras.Attachment.class,
                    IssueTemplate.class})
    static final class Issue implements Runnable {
        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "export", description = "Export editable Jira fields "
            + "and a lossless description to one Markdown document.")
    static final class Export extends Action {
        @Parameters(index = "0")
        String key;
        @Option(names = "--file", required = true, paramLabel = "ISSUE.md")
        Path file;

        @Override
        protected void execute() throws IOException {
            JiraIssueDocument.requireMarkdown(file);
            if (Files.exists(file)) {
                throw wrong("Export destination exists; choose a new file.");
            }
            var jira = client();
            var issue = jira.issue(key);
            var source = object(issue.get("fields"));
            var editable = object(jira.editMeta(key).get("fields"));
            var exported = new JsonObject();
            for (var entry : editable.entrySet()) {
                if (!List.of("description", "status", "created", "reporter", "updated").contains(entry.getKey())
                        && source.has(entry.getKey())) {
                    exported.add(entry.getKey(), source.get(entry.getKey()));
                }
            }
            var description = JiraMarkdown.fromJira(
                    source.get("description"), jira.apiVersion() == 3);
            var values = new JsonObject();
            values.addProperty("version", 1);
            values.addProperty("key", string(issue, "key"));
            values.addProperty("updated", string(source, "updated"));
            values.addProperty("mode", jira.cloud() ? "cloud" : "dc");
            values.addProperty("apiVersion", jira.apiVersion());
            values.addProperty("bodyFormat", jira.apiVersion() == 2 ? "wiki" : "markdown");
            values.addProperty("descriptionSha256", hash(description));
            exported = JiraIssueDocument.exportFields(exported, jira.cloud());
            for (var entry : exported.entrySet()) {
                values.add(entry.getKey(), entry.getValue());
            }
            if (source.has("project")) {
                values.add("project", JiraIssueDocument.exportFields(source, jira.cloud()).get("project"));
            }
            values.add("originalFields", exported.deepCopy());
            write(file, JiraIssueDocument.render(values, description));
            print("Exported " + key + " to " + file + ".");
        }
    }

    @Command(name = "update", description = "Update only changed exported "
            + "fields; reject changed remote issues by default.")
    static final class Update extends Action {
        @Mixin
        DescriptionFormat descriptionFormat = new DescriptionFormat();
        @Option(names = "--file", required = true, paramLabel = "ISSUE.md")
        Path file;
        @Option(names = "--force", description = "Override the remote "
                + "updated-time check (may overwrite recent changes).")
        boolean force;

        @Override
        protected void execute() throws IOException {
            var document = JiraIssueDocument.read(file);
            document.requireBaseline();
            var edit = new JiraIssueEditCommand();
            edit.key = document.string("key");
            edit.file = file;
            edit.force = force;
            edit.noInput = true;
            edit.descriptionFormat = descriptionFormat;
            edit.spec = spec;
            edit.execute();
            machineData = edit.machineData;
        }
    }

    @Command(name = "transitions",
            description = "List currently available status transitions.")
    static final class Transitions extends Action {
        @Parameters(index = "0")
        String key;

        @Override
        protected void execute() throws IOException {
            for (var value : client().transitions(key)
                    .getAsJsonArray("transitions")) {
                var transition = object(value);
                print(string(transition, "id") + " "
                        + string(transition, "name"));
            }
        }
    }

    @Command(name = "transition",
            description = "Change status using an available transition ID.")
    static final class Transition extends Action {
        @Parameters(index = "0")
        String key;
        @Parameters(index = "1")
        String id;

        @Override
        protected void execute() throws IOException {
            var jira = client();
            var allowed = false;
            for (var value : jira.transitions(key)
                    .getAsJsonArray("transitions")) {
                if (string(object(value), "id").equals(id)) {
                    allowed = true;
                }
            }
            if (!allowed) {
                throw wrong("Transition ID unavailable; run jira "
                        + "transitions " + key + " first.");
            }
            jira.transition(key, id);
            changed(key, true);
            print("Transitioned " + key + " with ID " + id + ".");
        }
    }

    @Command(name = "comment", description = "Add a Markdown comment.")
    static final class Comment extends Action {
        @Mixin
        BodyFormat bodyFormat = new BodyFormat();
        @Parameters(index = "0")
        String key;
        @Option(names = "--file", required = true, paramLabel = "BODY.md")
        Path file;

        @Override
        protected void execute() throws IOException {
            var jira = client();
            var created = jira.comment(key,
                    bodyFormat.toJira(text(file), jira));
            var result = new JsonObject();
            result.addProperty("key", key);
            if (created.has("id")) {
                result.add("commentId", created.get("id").deepCopy());
            }
            result.addProperty("created", true);
            machineData = result;
            print("Commented on " + key + ".");
        }
    }

    @Command(name = "comment-export",
            description = "Export a comment as Markdown and HOCON metadata.")
    static final class CommentExport extends Action {
        @Parameters(index = "0")
        String key;
        @Parameters(index = "1")
        String id;
        @Option(names = "--file", required = true, paramLabel = "BODY.md")
        Path file;

        @Override
        protected void execute() throws IOException {
            var meta = file.resolveSibling(
                    file.getFileName().toString().replaceFirst(
                            "\\.md$", ".conf"));
            if (!file.getFileName().toString().endsWith(".md")
                    || Files.exists(file) || Files.exists(meta)) {
                throw wrong("Choose unused .md and .conf files.");
            }
            var jira = client();
            var comment = jira.comment(key, id);
            var description = JiraMarkdown.fromJira(
                    comment.get("body"), jira.apiVersion() == 3);
            var values = new LinkedHashMap<String, Object>();
            values.put("version", 1);
            values.put("key", key);
            values.put("commentId", string(comment, "id"));
            values.put("updated", string(comment, "updated"));
            values.put("mode", jira.cloud() ? "cloud" : "dc");
            values.put("apiVersion", jira.apiVersion());
            values.put("bodyFormat", jira.apiVersion() == 2 ? "wiki" : "markdown");
            values.put("descriptionSha256", hash(description));
            write(file, description);
            try {
                write(meta, render(values));
            } catch (IOException exception) {
                Files.deleteIfExists(file);
                throw exception;
            }
            print("Exported comment " + id + " to " + file + ".");
        }
    }

    @Command(name = "comment-update",
            description = "Update an exported Markdown comment.")
    static final class CommentUpdate extends Action {
        @Mixin
        BodyFormat bodyFormat = new BodyFormat();
        @Parameters(index = "0")
        String key;
        @Parameters(index = "1")
        String id;
        @Option(names = "--file", required = true, paramLabel = "BODY.md")
        Path file;
        @Option(names = "--force")
        boolean force;

        @Override
        protected void execute() throws IOException {
            if (!file.getFileName().toString().endsWith(".md")) {
                throw wrong("Comment file must end in .md.");
            }
            var meta = file.resolveSibling(
                    file.getFileName().toString().replaceFirst(
                            "\\.md$", ".conf"));
            var data = load(meta);
            if (!data.hasPath("commentId") || !data.hasPath("updated")
                    || !data.hasPath("descriptionSha256")
                    || !data.hasPath("mode")
                    || !data.hasPath("key")
                    || !data.getString("key").equals(key)
                    || !data.getString("commentId").equals(id)) {
                throw wrong("Comment metadata does not match issue/ID.");
            }
            var jira = client();
            requireExportConfiguration(data, jira);
            var current = jira.comment(key, id);
            if (!force && !data.getString("updated").equals(
                    string(current, "updated"))) {
                throw wrong("Comment changed since export; re-export, "
                        + "or explicitly use --force to override.");
            }
            var content = text(file);
            if (!bodyFormat.changesFormat(data)
                    && hash(content).equals(data.getString("descriptionSha256"))) {
                print("No changes to upload.");
                return;
            }
            jira.editComment(key, id,
                    bodyFormat.toJira(content, jira, data));
            var result = new JsonObject();
            result.addProperty("key", key);
            result.addProperty("commentId", id);
            result.addProperty("changed", true);
            machineData = result;
            print("Updated comment " + id + ". "
                    + "Re-export before the next update.");
        }
    }

    @Command(name = "attach", description = "Upload a file as an "
            + "issue attachment.")
    static final class Attach extends Action {
        @Parameters(index = "0")
        String key;
        @Option(names = "--file", required = true, paramLabel = "PATH")
        Path file;

        @Override
        protected void execute() throws IOException {
            machineData = JiraIssueExtras.attachmentUploadData(
                    key, client().attach(key, file));
            print("Attached " + file.getFileName() + " to " + key + ".");
        }
    }

    private static final class NoIncludes implements ConfigIncluder {
        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            return this;
        }

        @Override
        public ConfigObject include(ConfigIncludeContext context,
                String name) {
            throw new ConfigException.Generic(
                    "Jira templates cannot include external resources.");
        }
    }
}
