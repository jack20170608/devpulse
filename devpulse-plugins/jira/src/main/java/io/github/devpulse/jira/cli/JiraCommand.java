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
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "jira", mixinStandardHelpOptions = true,
        versionProvider = JiraCommand.VersionProvider.class,
        description = "Manage Jira issues using HOCON and Markdown.",
        subcommands = {JiraCommand.Issue.class,
                JiraCommand.Init.class,
                JiraCommand.Show.class, JiraCommand.Export.class,
                JiraCommand.Update.class, JiraCommand.Transitions.class,
                JiraCommand.Transition.class, JiraCommand.Comment.class,
                JiraCommand.CommentExport.class,
                JiraCommand.CommentUpdate.class,
                JiraCommand.Attach.class})
public final class JiraCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Option(names = {"-c", "--config"}, defaultValue = ".jira.conf",
            scope = ScopeType.INHERIT, paramLabel = "FILE",
            description = "Jira connection HOCON file (default: .jira.conf "
                    + "in the current directory). Token comes from "
                    + "DEVPULSE_JIRA_TOKEN only.")
    private Path configFile;

    public static void main(String[] args) {
        var cli = new CommandLine(new JiraCommand())
                .setOut(new PrintWriter(System.out, true,
                        StandardCharsets.UTF_8))
                .setErr(new PrintWriter(System.err, true,
                        StandardCharsets.UTF_8));
        System.exit(cli.execute(args));
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

    static Path markdown(Path file) throws IOException {
        var name = file.getFileName().toString();
        if (!name.endsWith(".conf")) {
            throw new IOException("Issue metadata must be a .conf file.");
        }
        return file.resolveSibling(name.substring(0, name.length() - 5)
                + ".md");
    }

    static JsonObject fields(com.typesafe.config.Config config)
            throws IOException {
        if (!config.hasPath("fields")) {
            throw new IOException("Issue template needs a fields object.");
        }
        return JsonParser.parseString(
                ConfigValueFactory.fromMap(
                        config.getObject("fields").unwrapped()).render(
                        ConfigRenderOptions.concise())).getAsJsonObject();
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

    private static String hash(String content) {
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

    abstract static class Action implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        protected abstract void execute() throws IOException;

        JiraClient client() throws IOException {
            var root = (JiraCommand) spec.root().userObject();
            return new JiraClient(JiraConfiguration.load(root.configFile),
                    System.getenv("DEVPULSE_JIRA_TOKEN"));
        }

        @Override
        public final Integer call() {
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

    @Command(name = "init", description = "Create a HOCON/Markdown issue "
            + "template without contacting Jira.")
    static final class Init extends Action {
        @Option(names = "--file", required = true, paramLabel = "ISSUE.conf")
        Path file;

        @Override
        protected void execute() throws IOException {
            var body = markdown(file);
            write(body, "请替换此描述。 / Replace this description.\n");
            try {
                write(file, """
                        # 字段使用 Jira REST 格式；令牌仅从环境变量读取。
                        # Fields use Jira REST format.
                        # Token is read from the environment.
                        version = 1
                        fields {
                          project { key = "DEMO" }
                          issuetype { name = "Task" }
                          summary = "Replace this summary"
                          labels = []
                        }
                        """);
            } catch (IOException exception) {
                Files.deleteIfExists(body);
                throw exception;
            }
            print("Created " + file + " and " + body + ".");
        }
    }

    @Command(name = "issue", mixinStandardHelpOptions = true,
            description = "Manage Jira issues.",
            subcommands = {JiraCreateCommand.class, JiraIssueListCommand.class,
                    JiraIssueEditCommand.class})
    static final class Issue implements Runnable {
        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "show", description = "Show issue key, summary, "
            + "status, and updated time.")
    static final class Show extends Action {
        @Parameters(index = "0")
        String key;

        @Override
        protected void execute() throws IOException {
            var issue = client().issue(key);
            var fields = object(issue.get("fields"));
            print(string(issue, "key") + " "
                    + string(fields, "summary"));
            print("Status: " + string(object(fields.get("status")), "name"));
            print("Updated: " + string(fields, "updated"));
        }
    }

    @Command(name = "export", description = "Export editable Jira fields "
            + "and Markdown to new files.")
    static final class Export extends Action {
        @Parameters(index = "0")
        String key;
        @Option(names = "--file", required = true, paramLabel = "ISSUE.conf")
        Path file;

        @Override
        protected void execute() throws IOException {
            var body = markdown(file);
            if (Files.exists(body) || Files.exists(file)) {
                throw wrong("Export destination exists; choose new files.");
            }
            var jira = client();
            var issue = jira.issue(key);
            var source = object(issue.get("fields"));
            var editable = object(jira.editMeta(key).get("fields"));
            var exported = new JsonObject();
            for (var entry : editable.entrySet()) {
                if (!entry.getKey().equals("description")
                        && source.has(entry.getKey())) {
                    exported.add(entry.getKey(), source.get(entry.getKey()));
                }
            }
            var description = JiraMarkdown.fromJira(
                    source.get("description"), jira.cloud());
            var values = new LinkedHashMap<String, Object>();
            values.put("version", 1);
            values.put("key", string(issue, "key"));
            values.put("updated", string(source, "updated"));
            values.put("mode", jira.cloud() ? "cloud" : "dc");
            values.put("descriptionSha256", hash(description));
            values.put("fields", toMap(exported));
            values.put("originalFields", toMap(exported));
            var metadata = render(values);
            try {
                var roundTrip = ConfigFactory.parseString(metadata);
                if (!exported.equals(fields(roundTrip))) {
                    throw wrong("Editable Jira fields cannot be "
                            + "represented losslessly in HOCON.");
                }
            } catch (ConfigException exception) {
                throw wrong("Editable Jira fields cannot be "
                        + "represented in HOCON.");
            }
            write(body, description);
            try {
                write(file, metadata);
            } catch (IOException exception) {
                Files.deleteIfExists(body);
                throw exception;
            }
            print("Exported " + key + " to " + file + " and " + body + ".");
        }
    }

    @Command(name = "update", description = "Update only changed exported "
            + "fields; reject changed remote issues by default.")
    static final class Update extends Action {
        @Option(names = "--file", required = true, paramLabel = "ISSUE.conf")
        Path file;
        @Option(names = "--force", description = "Override the remote "
                + "updated-time check (may overwrite recent changes).")
        boolean force;

        @Override
        protected void execute() throws IOException {
            var data = load(file);
            if (!data.hasPath("key") || !data.hasPath("updated")
                    || !data.hasPath("originalFields")
                    || !data.hasPath("descriptionSha256")
                    || !data.hasPath("mode")) {
                throw wrong("Use jira export to create an update file.");
            }
            var jira = client();
            if (!data.getString("mode").equals(jira.cloud()
                    ? "cloud" : "dc")) {
                throw wrong("Exported Jira mode does not match environment.");
            }
            var current = jira.issue(data.getString("key"));
            var remote = object(current.get("fields"));
            if (!force && !data.getString("updated").equals(
                    string(remote, "updated"))) {
                throw wrong("Issue changed since export; re-export, "
                        + "or explicitly use --force to override.");
            }
            var original = object(JsonParser.parseString(
                    data.getObject("originalFields").render(
                            ConfigRenderOptions.concise())));
            var edited = fields(data);
            var changes = new JsonObject();
            for (var entry : edited.entrySet()) {
                var name = entry.getKey();
                if (!original.has(name)) {
                    throw wrong("Field " + name
                            + " was not exported as editable.");
                }
                if (!entry.getValue().equals(original.get(name))) {
                    changes.add(name, entry.getValue());
                }
            }
            for (var entry : original.entrySet()) {
                if (!edited.has(entry.getKey())) {
                    throw wrong("Cannot remove exported field "
                            + entry.getKey() + "; use null explicitly.");
                }
            }
            var description = text(markdown(file));
            if (!hash(description).equals(
                    data.getString("descriptionSha256"))) {
                changes.add("description", JiraMarkdown.toJira(description,
                        jira.cloud()));
            }
            if (changes.isEmpty()) {
                print("No changes to upload.");
                return;
            }
            jira.edit(data.getString("key"), changes);
            print("Updated " + data.getString("key") + ". "
                    + "Re-export before the next update.");
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
            print("Transitioned " + key + " with ID " + id + ".");
        }
    }

    @Command(name = "comment", description = "Add a Markdown comment.")
    static final class Comment extends Action {
        @Parameters(index = "0")
        String key;
        @Option(names = "--file", required = true, paramLabel = "BODY.md")
        Path file;

        @Override
        protected void execute() throws IOException {
            var jira = client();
            jira.comment(key, JiraMarkdown.toJira(text(file), jira.cloud()));
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
                    comment.get("body"), jira.cloud());
            var values = new LinkedHashMap<String, Object>();
            values.put("version", 1);
            values.put("key", key);
            values.put("commentId", string(comment, "id"));
            values.put("updated", string(comment, "updated"));
            values.put("mode", jira.cloud() ? "cloud" : "dc");
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
            if (!data.getString("mode").equals(jira.cloud()
                    ? "cloud" : "dc")) {
                throw wrong("Exported Jira mode does not match environment.");
            }
            var current = jira.comment(key, id);
            if (!force && !data.getString("updated").equals(
                    string(current, "updated"))) {
                throw wrong("Comment changed since export; re-export, "
                        + "or explicitly use --force to override.");
            }
            var content = text(file);
            if (hash(content).equals(data.getString("descriptionSha256"))) {
                print("No changes to upload.");
                return;
            }
            jira.editComment(key, id,
                    JiraMarkdown.toJira(content, jira.cloud()));
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
            client().attach(key, file);
            print("Attached " + file.getFileName() + " to " + key + ".");
        }
    }

    private static Map<String, Object> toMap(JsonObject value) {
        return new com.google.gson.Gson().fromJson(value, Map.class);
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
