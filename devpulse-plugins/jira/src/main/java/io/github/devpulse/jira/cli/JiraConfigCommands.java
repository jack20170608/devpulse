package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;
import io.github.devpulse.jira.JiraClient;
import io.github.devpulse.jira.JiraConfiguration;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Connection configuration: create, inspect, edit single keys, and verify. */
@Command(
        name = "config",
        mixinStandardHelpOptions = true,
        description = "Create, show, edit, and verify the Jira connection configuration.",
        subcommands = {
            JiraConfigCommands.Init.class,
            JiraConfigCommands.Show.class,
            JiraConfigCommands.Set.class,
            JiraConfigCommands.Unset.class,
            JiraConfigCommands.Verify.class
        })
final class JiraConfigCommands implements Runnable {
    static final List<String> KEYS =
            List.of("url", "mode", "email", "apiVersion", "project", "profile");
    private static final String PROJECT_KEY = "[A-Za-z][A-Za-z0-9_]*";

    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Fields supplied to the creation wizard; null values are prompted for. */
    record Draft(String url, String mode, Integer apiVersion, String email, String project) {}

    /** File for creation: -g, then --config, then the current directory. */
    static Path creationFile(JiraCommand.Action action, boolean global) throws IOException {
        var explicit = action.explicitConfigFile();
        if (global && explicit != null) {
            throw wrong("Choose either --global or --config, not both.");
        }
        return global
                ? JiraConfiguration.globalFile()
                : explicit == null ? JiraConfiguration.localFile() : explicit;
    }

    /** File in use: -g, then --config, then local before global. */
    static Path activeFile(JiraCommand.Action action, boolean global) throws IOException {
        var explicit = action.explicitConfigFile();
        if (global && explicit != null) {
            throw wrong("Choose either --global or --config, not both.");
        }
        return global ? JiraConfiguration.globalFile() : JiraConfiguration.find(explicit);
    }

    static boolean isGlobal(Path file) {
        return file.toAbsolutePath()
                .normalize()
                .equals(JiraConfiguration.globalFile().toAbsolutePath().normalize());
    }

    static String absolute(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    static String token() {
        return System.getenv("JIRA_API_TOKEN");
    }

    static boolean hasToken() {
        var token = token();
        return token != null && !token.isBlank();
    }

    /** Prompt for missing fields, then create the file; never overwrites. */
    static void create(Path file, Draft supplied, Prompter prompt) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw wrong(
                    "Configuration already exists: "
                            + file
                            + "; use jira config set KEY VALUE to change it.");
        }
        var url =
                prompt.value(
                        supplied.url(),
                        "Jira URL (--url)",
                        null,
                        candidate -> validate("url", candidate));
        var mode =
                prompt.value(
                        supplied.mode(),
                        "Mode (--mode)",
                        "cloud",
                        candidate -> validate("mode", candidate));
        var apiVersion = supplied.apiVersion();
        if (apiVersion != null) {
            validate("apiVersion", apiVersion.toString());
        } else if (prompt.interactive()) {
            var selected =
                    prompt.value(
                            null,
                            "API version (2/3; auto uses mode default "
                                    + (mode.equals("cloud") ? 3 : 2)
                                    + ")",
                            "auto",
                            candidate -> {
                                if (!candidate.equals("auto")) {
                                    validate("apiVersion", candidate);
                                }
                            });
            apiVersion = selected.equals("auto") ? null : Integer.valueOf(selected);
        }
        var email = supplied.email();
        if (mode.equals("cloud") || email != null) {
            email =
                    prompt.value(
                            email,
                            "Cloud email (--email)",
                            null,
                            candidate -> validate("email", candidate));
        }
        var project =
                prompt.value(
                        supplied.project(),
                        "Default project key (--project)",
                        null,
                        candidate -> validate("project", candidate));
        var values = new LinkedHashMap<String, Object>();
        values.put("url", url);
        values.put("mode", mode);
        if (apiVersion != null) {
            values.put("apiVersion", apiVersion);
        }
        if (email != null) {
            values.put("email", email);
        }
        values.put("project", project);
        var content =
                ConfigValueFactory.fromMap(values)
                                .render(
                                        ConfigRenderOptions.defaults()
                                                .setJson(false)
                                                .setComments(false)
                                                .setOriginComments(false))
                        + "\n";
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(
                file,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    /** GET myself with the given configuration; returns the identity summary. */
    static JsonObject verify(JiraConfiguration connection) throws IOException {
        if (!hasToken()) {
            throw wrong("Set JIRA_API_TOKEN to test the Jira connection.");
        }
        var jira = new JiraClient(connection, token());
        var identifier = jira.cloud() ? "accountId" : "name";
        var me = jira.myself();
        var result = new JsonObject();
        result.addProperty(
                identifier,
                JiraCommand.nonblank(JiraCommand.string(me, identifier), "Current user"));
        if (me.has("displayName") && me.get("displayName").isJsonPrimitive()) {
            result.addProperty("displayName", me.get("displayName").getAsString());
        }
        return result;
    }

    static String identity(JsonObject verification) {
        return verification.has("displayName")
                ? verification.get("displayName").getAsString()
                : verification.has("accountId")
                        ? verification.get("accountId").getAsString()
                        : verification.get("name").getAsString();
    }

    static void validate(String key, String value) throws IOException {
        if (!KEYS.contains(key)) {
            throw wrong(
                    "Unknown configuration key: "
                            + key
                            + ". Use one of "
                            + String.join(", ", KEYS)
                            + ".");
        }
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw wrong("Use a nonblank value without control characters.");
        }
        switch (key) {
            case "url" -> JiraConfiguration.validateUrl(value);
            case "mode" -> {
                if (!value.equals("cloud") && !value.equals("dc")) {
                    throw wrong("Mode must be cloud or dc.");
                }
            }
            case "apiVersion" -> {
                if (!value.equals("2") && !value.equals("3")) {
                    throw wrong("API version must be 2 or 3.");
                }
            }
            case "project" -> {
                if (!value.matches(PROJECT_KEY)) {
                    throw wrong(
                            "Project key must start with a letter "
                                    + "and contain only letters, digits, or _.");
                }
            }
            case "profile" -> JiraProfiles.name(value);
            default -> {}
        }
    }

    /**
     * Set (value non-null) or remove one top-level key by editing only its line, keeping other
     * lines, comments, and formatting. Returns whether the file changed.
     */
    static boolean edit(Path file, String key, String value) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw wrong(
                    "Jira configuration not found: "
                            + file
                            + ". Run jira config init (or jira config init -g).");
        }
        if (!KEYS.contains(key)) {
            throw wrong(
                    "Unknown configuration key: "
                            + key
                            + ". Use one of "
                            + String.join(", ", KEYS)
                            + ".");
        }
        if (value != null) {
            validate(key, value);
        } else if (key.equals("url") || key.equals("mode")) {
            throw wrong(key + " is required and cannot be unset.");
        }
        var current = JiraConfiguration.load(file);
        if (value == null && key.equals("email") && current.mode().equals("cloud")) {
            throw wrong("email is required in cloud mode and cannot be unset.");
        }
        var original = Files.readString(file, StandardCharsets.UTF_8);
        var newline = original.contains("\r\n") ? "\r\n" : "\n";
        var lines = new ArrayList<>(List.of(original.split("\\r?\\n", -1)));
        var pattern =
                Pattern.compile("^(\\s*(\"?)" + Pattern.quote(key) + "\\2\\s*(?:=|:)\\s*)(.*)$");
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).matches()) {
                if (index >= 0) {
                    throw wrong(
                            key
                                    + " is defined more than once in "
                                    + file
                                    + "; edit the file directly.");
                }
                index = i;
            }
        }
        var rendered =
                value == null
                        ? null
                        : key.equals("apiVersion") ? value : new JsonPrimitive(value).toString();
        if (index < 0) {
            if (value == null) {
                return false;
            }
            var line = key + " = " + rendered;
            if (lines.getLast().isEmpty()) {
                lines.add(lines.size() - 1, line);
            } else {
                lines.add(line);
                lines.add("");
            }
        } else {
            var matcher = pattern.matcher(lines.get(index));
            matcher.matches();
            var rest = matcher.group(3);
            if (rest.startsWith("\"\"\"")
                    || rest.startsWith("{")
                    || rest.startsWith("[")
                    || rest.contains("${")) {
                throw wrong(
                        key
                                + " in "
                                + file
                                + " is not a single-line value; edit the file directly.");
            }
            if (value == null) {
                lines.remove(index);
            } else {
                var comment = comment(rest);
                lines.set(
                        index,
                        matcher.group(1) + rendered + (comment.isEmpty() ? "" : " " + comment));
            }
        }
        var updated = String.join(newline, lines);
        if (updated.equals(original)) {
            return false;
        }
        replace(file, updated, key, value);
        return true;
    }

    /** Trailing # or // comment outside double quotes, or empty. */
    private static String comment(String rest) {
        boolean quoted = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\' && quoted) {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted
                    && (c == '#'
                            || (c == '/' && i + 1 < rest.length() && rest.charAt(i + 1) == '/'))) {
                return rest.substring(i).strip();
            }
        }
        return "";
    }

    /** Validate the edited content as a whole, then replace the file atomically. */
    private static void replace(Path file, String content, String key, String value)
            throws IOException {
        var directory = file.toAbsolutePath().getParent();
        var staging = Files.createTempFile(directory, ".jira-config-", ".conf");
        try {
            Files.writeString(staging, content, StandardCharsets.UTF_8);
            JiraConfiguration.load(staging);
            try {
                var parsed = ConfigFactory.parseString(content);
                boolean applied =
                        value == null
                                ? !parsed.hasPathOrNull(key)
                                : key.equals("apiVersion")
                                        ? parsed.getInt(key) == Integer.parseInt(value)
                                        : parsed.getString(key).equals(value);
                if (!applied) {
                    throw wrong("Could not apply " + key + " safely; edit " + file + " directly.");
                }
            } catch (ConfigException exception) {
                throw wrong("Could not apply " + key + " safely; edit " + file + " directly.");
            }
            Files.move(
                    staging,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /** Console prompts written to stderr; disabled without a console or with --no-input. */
    static final class Prompter {
        private final PrintWriter err;
        private final boolean interactive;

        Prompter(CommandSpec spec, boolean noInput) {
            this.err = spec.commandLine().getErr();
            this.interactive = !noInput && System.console() != null;
        }

        boolean interactive() {
            return interactive;
        }

        boolean confirm(String question) {
            if (!interactive) {
                return false;
            }
            err.print(question + " [Y/n]: ");
            err.flush();
            var answer = System.console().readLine();
            return answer != null
                    && (answer.isBlank()
                            || answer.strip().equalsIgnoreCase("y")
                            || answer.strip().equalsIgnoreCase("yes"));
        }

        String value(String supplied, String label, String fallback, Validator validator)
                throws IOException {
            if (supplied != null) {
                validator.validate(supplied);
                return supplied;
            }
            if (!interactive) {
                if (fallback == null) {
                    throw wrong(
                            "Missing "
                                    + label
                                    + "; supply it as a flag "
                                    + "or run the command in a terminal.");
                }
                validator.validate(fallback);
                return fallback;
            }
            while (true) {
                err.print(label + (fallback == null ? "" : " [" + fallback + "]") + ": ");
                err.flush();
                var answer = System.console().readLine();
                if (answer == null) {
                    throw wrong("Input ended; configuration was not created.");
                }
                if (answer.isBlank() && fallback != null) {
                    answer = fallback;
                }
                try {
                    validator.validate(answer);
                    return answer;
                } catch (IOException exception) {
                    err.println(exception.getMessage());
                }
            }
        }
    }

    @FunctionalInterface
    interface Validator {
        void validate(String value) throws IOException;
    }

    @Command(
            name = "init",
            mixinStandardHelpOptions = true,
            description =
                    "Create the connection configuration from flags or prompts; "
                            + "never stores a token or overwrites an existing file.")
    static final class Init extends JiraCommand.Action {
        @Option(
                names = {"-g", "--global"},
                description =
                        "Create ~/.jira/"
                                + JiraConfiguration.FILE_NAME
                                + " instead of .jira/"
                                + JiraConfiguration.FILE_NAME
                                + " in the current directory.")
        boolean global;

        @Option(names = "--url", paramLabel = "URL", description = "Jira base URL.")
        String url;

        @Option(names = "--mode", paramLabel = "MODE", description = "cloud (default) or dc.")
        String mode;

        @Option(
                names = "--api-version",
                paramLabel = "N",
                description = "Optional API version: 2 or 3; omit for mode defaults.")
        Integer apiVersion;

        @Option(names = "--email", paramLabel = "EMAIL", description = "Cloud account email.")
        String email;

        @Option(
                names = {"-p", "--project"},
                paramLabel = "KEY",
                description = "Default project key for issue create/list and env refresh.")
        String project;

        @Option(
                names = "--no-input",
                description = "Never prompt; supply required fields using flags.")
        boolean noInput;

        @Override
        protected void execute() throws IOException {
            var file = creationFile(this, global);
            create(
                    file,
                    new Draft(url, mode, apiVersion, email, project),
                    new Prompter(spec, noInput));
            var result = new JsonObject();
            result.addProperty("config", absolute(file));
            result.addProperty("scope", isGlobal(file) ? "global" : "local");
            machineData = result;
            print("Created " + file + ".");
            if (!hasToken()) {
                print(
                        "Set JIRA_API_TOKEN, then run jira config verify"
                                + (global ? " -g" : "")
                                + ".");
            }
        }
    }

    @Command(
            name = "show",
            mixinStandardHelpOptions = true,
            description =
                    "Show the configuration file in use, its effective values, "
                            + "and whether JIRA_API_TOKEN is set (never its value).")
    static final class Show extends JiraCommand.Action {
        @Option(
                names = {"-g", "--global"},
                description = "Show ~/.jira configuration.")
        boolean global;

        @Override
        protected void execute() throws IOException {
            var file = activeFile(this, global);
            var connection = JiraConfiguration.load(file);
            var values = new JsonObject();
            values.addProperty("url", connection.url());
            values.addProperty("mode", connection.mode());
            values.addProperty("apiVersion", connection.apiVersion());
            values.addProperty("email", connection.email());
            values.addProperty("project", connection.project());
            values.addProperty("profile", connection.profile());
            var result = new JsonObject();
            result.addProperty("config", absolute(file));
            result.addProperty("scope", isGlobal(file) ? "global" : "local");
            result.add("values", values);
            result.addProperty("tokenSet", hasToken());
            machineData = result;
            print("config\t" + absolute(file) + " (" + result.get("scope").getAsString() + ")");
            for (var key : KEYS) {
                var entry = values.get(key);
                print(
                        key
                                + "\t"
                                + (entry == null || entry.isJsonNull() ? "" : entry.getAsString()));
            }
            print("token\tJIRA_API_TOKEN " + (hasToken() ? "is set" : "is not set"));
        }
    }

    @Command(
            name = "set",
            mixinStandardHelpOptions = true,
            description =
                    "Set one key (url, mode, email, apiVersion, project, profile), "
                            + "editing only its line and keeping comments and formatting.")
    static final class Set extends JiraCommand.Action {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;

        @Parameters(index = "1", paramLabel = "VALUE")
        String value;

        @Option(
                names = {"-g", "--global"},
                description = "Edit ~/.jira configuration.")
        boolean global;

        @Override
        protected void execute() throws IOException {
            var file = activeFile(this, global);
            boolean changed = edit(file, key, value);
            changed(key, changed);
            ((JsonObject) machineData).addProperty("config", absolute(file));
            print((changed ? "Set " : "Unchanged ") + key + " in " + file + ".");
        }
    }

    @Command(
            name = "unset",
            mixinStandardHelpOptions = true,
            description = "Remove one optional key (email, apiVersion, project, profile).")
    static final class Unset extends JiraCommand.Action {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;

        @Option(
                names = {"-g", "--global"},
                description = "Edit ~/.jira configuration.")
        boolean global;

        @Override
        protected void execute() throws IOException {
            var file = activeFile(this, global);
            boolean changed = edit(file, key, null);
            changed(key, changed);
            ((JsonObject) machineData).addProperty("config", absolute(file));
            print((changed ? "Removed " : "Not set: ") + key + " in " + file + ".");
        }
    }

    @Command(
            name = "verify",
            mixinStandardHelpOptions = true,
            description = "Test the connection with GET myself (requires JIRA_API_TOKEN).")
    static final class Verify extends JiraCommand.Action {
        @Option(
                names = {"-g", "--global"},
                description = "Use ~/.jira configuration.")
        boolean global;

        @Override
        protected void execute() throws IOException {
            var file = activeFile(this, global);
            var connection = JiraConfiguration.load(file);
            var verification = verify(connection);
            var result = new JsonObject();
            result.addProperty("config", absolute(file));
            result.addProperty("url", connection.url());
            result.add("connection", verification);
            machineData = result;
            print(
                    "Connection OK as "
                            + identity(verification)
                            + " ("
                            + connection.url()
                            + ", "
                            + connection.mode()
                            + ", API v"
                            + connection.apiVersion()
                            + ").");
        }
    }
}
