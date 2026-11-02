package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;
import io.github.devpulse.jira.JiraConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Configure a Jira connection step by step, without "
                + "contacting Jira or storing a token.")
public final class JiraInitCommand extends JiraCommand.Action {
    @Option(names = {"-g", "--global"},
            description = "Create ~/.jira/local_jira_config.conf "
                    + "instead of a file in the current directory.")
    boolean global;
    @Option(names = "--url", paramLabel = "URL",
            description = "Jira base URL.")
    String url;
    @Option(names = "--mode", paramLabel = "MODE",
            description = "cloud (default) or dc.")
    String mode;
    @Option(names = "--api-version", paramLabel = "N",
            description = "Optional API version: 2 or 3; omit for mode defaults.")
    Integer apiVersion;
    @Option(names = "--email", paramLabel = "EMAIL",
            description = "Cloud account email.")
    String email;
    @Option(names = {"-p", "--project"}, paramLabel = "KEY",
            description = "Default project key for issue create/list.")
    String project;
    @Option(names = "--no-input",
            description = "Never prompt; supply required fields using flags.")
    boolean noInput;

    @Override
    protected void execute() throws IOException {
        if (global && explicitConfigFile() != null) {
            throw wrong("Choose either --global or --config, not both.");
        }
        var file = global ? JiraConfiguration.globalFile()
                : explicitConfigFile() == null
                ? Path.of(JiraConfiguration.FILE_NAME) : explicitConfigFile();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw wrong("Configuration already exists: " + file
                    + "; existing files are never overwritten.");
        }
        url = value(url, "Jira URL (--url)", null,
                JiraConfiguration::validateUrl);
        mode = value(mode, "Mode (--mode)", "cloud", candidate -> {
            if (!candidate.equals("cloud") && !candidate.equals("dc")) {
                throw wrong("Mode must be cloud or dc.");
            }
        });
        if (apiVersion != null) {
            version(apiVersion.toString());
        } else if (!noInput && System.console() != null) {
            var selected = value(null,
                    "API version (2/3; auto uses mode default "
                            + (mode.equals("cloud") ? 3 : 2) + ")",
                    "auto", candidate -> {
                        if (!candidate.equals("auto")) {
                            version(candidate);
                        }
                    });
            if (!selected.equals("auto")) {
                apiVersion = Integer.valueOf(selected);
            }
        }
        if (mode.equals("cloud") || email != null) {
            email = value(email, "Cloud email (--email)", null,
                    JiraInitCommand::textValue);
        }
        project = value(project, "Default project key (--project)", null,
                candidate -> {
                    if (!candidate.matches("[A-Za-z][A-Za-z0-9_]*")) {
                        throw wrong("Project key must start with a letter "
                                + "and contain only letters, digits, or _.");
                    }
                });
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
        var content = ConfigValueFactory.fromMap(values).render(
                ConfigRenderOptions.defaults().setJson(false)
                        .setComments(false).setOriginComments(false)) + "\n";
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        print("Created " + file + ". Set JIRA_API_TOKEN in the environment "
                + "before contacting Jira.");
    }

    private String value(String supplied, String label, String fallback,
            Validator validator) throws IOException {
        if (supplied != null) {
            textValue(supplied);
            validator.validate(supplied);
            return supplied;
        }
        var console = System.console();
        if (noInput || console == null) {
            if (fallback == null) {
                throw wrong("Missing " + label + "; supply it as a flag "
                        + "or run init in a terminal.");
            }
            validator.validate(fallback);
            return fallback;
        }
        while (true) {
            spec.commandLine().getErr().print(label
                    + (fallback == null ? "" : " [" + fallback + "]") + ": ");
            spec.commandLine().getErr().flush();
            var answer = console.readLine();
            if (answer == null) {
                throw wrong("Input ended; configuration was not created.");
            }
            if (answer.isBlank() && fallback != null) {
                answer = fallback;
            }
            try {
                textValue(answer);
                validator.validate(answer);
                return answer;
            } catch (IOException exception) {
                spec.commandLine().getErr().println(exception.getMessage());
            }
        }
    }

    private static void textValue(String value) throws IOException {
        if (value.isBlank()
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw wrong("Use a nonblank value without control characters.");
        }
    }

    private static void version(String value) throws IOException {
        if (!value.equals("2") && !value.equals("3")) {
            throw wrong("API version must be 2 or 3.");
        }
    }

    @FunctionalInterface
    private interface Validator {
        void validate(String value) throws IOException;
    }
}
