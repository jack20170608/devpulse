package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.devpulse.jira.JiraClient;
import io.github.devpulse.jira.JiraConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Project environment snapshots read by bundled AI skills. */
@Command(name = "env", mixinStandardHelpOptions = true,
        description = "Collect and inspect project environment snapshots "
                + "(issue types, epics, boards, sprints) used by AI skills.",
        subcommands = {JiraEnvironmentCommands.Refresh.class,
                JiraEnvironmentCommands.Show.class})
final class JiraEnvironmentCommands implements Runnable {
    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * Collect a snapshot and replace the saved one. Snapshots follow the configuration
     * scope: global when the configuration is ~/.jira, otherwise local.
     */
    static JsonObject refresh(Path file, JiraConfiguration connection, String project)
            throws IOException {
        var key = project != null ? project : connection.project();
        if (key == null) {
            throw wrong("Set project in " + file + " (jira config set project KEY) "
                    + "or pass --project to collect the environment.");
        }
        if (!JiraConfigCommands.hasToken()) {
            throw wrong("Set JIRA_API_TOKEN to collect the environment.");
        }
        var jira = new JiraClient(connection, JiraConfigCommands.token());
        var snapshot = new JiraEnvironment(connection, jira::request, jira::agile)
                .collect(JiraCatalogCommands.project(key, null));
        var saved = JiraEnvironment.save(JiraConfigCommands.isGlobal(file), snapshot);
        var summary = JiraEnvironment.summary(snapshot);
        summary.addProperty("file", JiraConfigCommands.absolute(saved));
        return summary;
    }

    static void report(JiraCommand.Action action, JsonObject summary) {
        var project = summary.getAsJsonObject("project");
        action.print("Environment " + project.get("key").getAsString() + " captured "
                + summary.get("capturedAt").getAsString() + " in "
                + summary.get("file").getAsString() + ".");
        for (var section : JiraEnvironment.SECTIONS) {
            var status = summary.getAsJsonObject(section);
            action.print(section + "\t" + status.get("count").getAsInt() + "\t"
                    + (status.get("complete").getAsBoolean() ? "complete"
                    : "incomplete (" + status.get("status").getAsString() + ")"));
        }
    }

    @Command(name = "refresh", mixinStandardHelpOptions = true,
            description = "Read-only collection of a project's issue types, epics, boards, "
                    + "and sprints into .jira/environment/KEY.json (~/.jira when the "
                    + "configuration is global), replacing an older snapshot.")
    static final class Refresh extends JiraCommand.Action {
        @Option(names = {"-p", "--project"}, paramLabel = "KEY",
                description = "Project key; defaults to the configuration's project.")
        String project;
        @Option(names = {"-g", "--global"},
                description = "Use ~/.jira configuration and save under ~/.jira/environment.")
        boolean global;

        @Override
        protected void execute() throws IOException {
            var file = JiraConfigCommands.activeFile(this, global);
            var summary = refresh(file, JiraConfiguration.load(file), project);
            machineData = summary;
            report(this, summary);
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true,
            description = "Summarize a saved snapshot: local .jira/environment first, "
                    + "then ~/.jira/environment.")
    static final class Show extends JiraCommand.Action {
        @Option(names = {"-p", "--project"}, paramLabel = "KEY",
                description = "Project key; defaults to the configuration's project.")
        String project;
        @Option(names = {"-g", "--global"},
                description = "Use ~/.jira configuration and read only ~/.jira/environment.")
        boolean global;

        @Override
        protected void execute() throws IOException {
            var key = project;
            if (key == null) {
                var file = JiraConfigCommands.activeFile(this, global);
                key = Files.isRegularFile(file) ? JiraConfiguration.load(file).project() : null;
                if (key == null) {
                    throw wrong("Pass --project KEY or set project in the configuration.");
                }
            }
            key = JiraCatalogCommands.project(key, null);
            Path found = null;
            for (var scope : global ? new boolean[] {true} : new boolean[] {false, true}) {
                var candidate = JiraEnvironment.file(scope, key);
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                throw wrong("No environment snapshot for " + key
                        + ". Run jira env refresh" + (global ? " -g" : "") + ".");
            }
            final JsonObject summary;
            try {
                summary = JiraEnvironment.summary(JsonParser.parseString(
                        Files.readString(found, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (RuntimeException exception) {
                throw wrong("Invalid environment snapshot: " + found
                        + ". Run jira env refresh to replace it.");
            }
            summary.addProperty("file", JiraConfigCommands.absolute(found));
            machineData = summary;
            report(this, summary);
        }
    }
}
