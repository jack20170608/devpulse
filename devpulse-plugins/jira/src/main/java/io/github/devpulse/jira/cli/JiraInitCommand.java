package io.github.devpulse.jira.cli;

import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Guided onboarding composed of config init, the default profile, config verify, and env refresh. */
@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Guided setup: create the connection configuration when missing "
                + "(jira config init), prepare the default template profile, then offer "
                + "to verify the connection (jira config verify) and collect the project "
                + "environment (jira env refresh). Existing files are kept.")
public final class JiraInitCommand extends JiraCommand.Action {
    @Option(names = {"-g", "--global"},
            description = "Set up everything under ~/.jira instead of the current directory.")
    boolean global;
    @Option(names = "--language", defaultValue = "en", paramLabel = "en|zh",
            description = "Language of the default profile's bundled templates: "
                    + "${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    JiraSkillCommands.Language language;
    @Option(names = "--no-input",
            description = "Never prompt; the configuration must already exist.")
    boolean noInput;

    @Override
    protected void execute() throws IOException {
        var file = JiraConfigCommands.creationFile(this, global);
        var prompt = new JiraConfigCommands.Prompter(spec, noInput);
        boolean configExists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        var suffix = global ? " -g" : "";
        if (!configExists) {
            if (!prompt.interactive()) {
                throw JiraCommand.wrong("Configuration not found: " + file
                        + ". Run jira config init" + suffix
                        + " --url URL --email EMAIL --project KEY, or run jira init in a terminal.");
            }
            JiraConfigCommands.create(file,
                    new JiraConfigCommands.Draft(null, null, null, null, null), prompt);
        }
        var profile = JiraProfiles.root(global).resolve(JiraProfiles.DEFAULT);
        var created = JiraProfiles.materialize(profile, null, language);
        var result = new JsonObject();
        result.addProperty("config", JiraConfigCommands.absolute(file));
        result.addProperty("configCreated", !configExists);
        result.add("profile", new JiraProfiles.Location(JiraProfiles.DEFAULT,
                global ? "global" : "local", profile).json());
        result.add("createdFiles", JiraProfiles.paths(created));
        machineData = result;
        print((configExists ? "Kept existing " : "Created ") + file + ".");
        print((created.isEmpty() ? "Kept existing" : "Prepared")
                + " default profile at " + profile.toAbsolutePath().normalize() + ".");
        var next = "jira config verify" + suffix + " and jira env refresh" + suffix;
        if (!JiraConfigCommands.hasToken()) {
            print("Set JIRA_API_TOKEN, then run " + next + ".");
            return;
        }
        if (!prompt.confirm("Test the Jira API connection now (GET myself)?")) {
            print("Next: " + next + ".");
            return;
        }
        var connection = JiraConfiguration.load(file);
        var verification = JiraConfigCommands.verify(connection);
        result.add("connection", verification);
        print("Connection OK as " + JiraConfigCommands.identity(verification) + ".");
        var key = connection.project();
        if (key == null) {
            print("Set a project (jira config set project KEY" + suffix
                    + "), then run jira env refresh" + suffix + ".");
            return;
        }
        if (!prompt.confirm("Collect the " + key + " environment (issue types, epics, "
                + "boards, sprints) into " + JiraEnvironment.file(global, key) + "?")) {
            return;
        }
        var summary = JiraEnvironmentCommands.refresh(file, connection, null);
        result.add("environment", summary);
        JiraEnvironmentCommands.report(this, summary);
    }
}
