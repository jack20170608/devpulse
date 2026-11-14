package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.nonblank;
import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "assign", mixinStandardHelpOptions = true,
        description = "Assign a user to an issue; x unassigns it.")
public final class JiraIssueAssignCommand extends JiraCommand.Action {
    @Parameters(index = "0", arity = "0..1", paramLabel = "KEY",
            description = "Issue key or ID; prompted when omitted.")
    String key;
    @Parameters(index = "1", arity = "0..1", paramLabel = "USER",
            description = "Display name or DC username; me=self, x=unassigned. "
                    + "Ambiguous matches may prompt unless --no-input is set.")
    String assignee;
    @Option(names = "--no-input",
            description = "Never prompt; KEY and USER are required.")
    boolean noInput;

    @Override
    protected void execute() throws IOException {
        var interactive = !noInput && System.console() != null;
        if ((key == null || assignee == null)
                && (noInput || System.console() == null)) {
            throw wrong("Supply KEY and USER; interactive assignment "
                    + "requires a terminal.");
        }
        if (key == null) {
            key = prompt("Issue key");
        }
        nonblank(key, "KEY");
        if (assignee == null) {
            assignee = prompt("Assignee (name/me/x)");
        }
        nonblank(assignee, "USER");
        var jira = client();
        var identifier = jira.cloud() ? "accountId" : "name";
        String user = null;
        if (assignee.equals("me")) {
            user = nonblank(string(jira.myself(), identifier), "Current user");
        } else if (!assignee.equals("x")) {
            user = resolve(jira, identifier, interactive);
        }
        jira.assign(key, user);
        var result = new JsonObject();
        result.addProperty("key", key);
        result.addProperty("assignee", user);
        result.addProperty("changed", true);
        machineData = result;
        print(user == null ? "Unassigned " + key + "."
                : "Assigned " + key + " to " + assignee + ".");
    }

    private String resolve(JiraClient jira, String identifier,
            boolean interactive) throws IOException {
        var results = jira.assignableUsers(key, assignee);
        if (results.size() >= 50) {
            throw wrong("Too many assignable users matched; "
                    + "use a more specific name.");
        }
        var users = new ArrayList<JsonObject>();
        for (var result : results) {
            var user = object(result);
            nonblank(string(user, identifier), "User identifier");
            if (!users.contains(user)) {
                users.add(user);
            }
        }
        var exact = users.stream().filter(user ->
                matches(user, identifier, assignee)
                        || matches(user, "displayName", assignee)).toList();
        List<JsonObject> candidates = exact.isEmpty() ? users : exact;
        if (candidates.isEmpty()) {
            throw wrong("No assignable user matched; check the name "
                    + "and Jira permissions.");
        }
        if (candidates.size() == 1) {
            return string(candidates.getFirst(), identifier);
        }
        if (!interactive) {
            throw wrong("Multiple assignable users matched; use a more "
                    + "specific name or rerun in a terminal "
                    + "without --no-input to select interactively.");
        }
        var err = spec.commandLine().getErr();
        for (var index = 0; index < candidates.size(); index++) {
            var user = candidates.get(index);
            var label = user.has("displayName")
                    && !user.get("displayName").isJsonNull()
                    ? string(user, "displayName") : string(user, identifier);
            err.println((index + 1) + ". " + safe(label) + " ("
                    + safe(string(user, identifier)) + ")");
        }
        while (true) {
            var answer = prompt("Select user (1-" + candidates.size() + ")");
            try {
                var index = Integer.parseInt(answer) - 1;
                if (index >= 0 && index < candidates.size()) {
                    return string(candidates.get(index), identifier);
                }
            } catch (NumberFormatException ignored) {
                // Retry invalid selections without modifying the issue.
            }
            err.println("Enter a number from 1 to " + candidates.size() + ".");
        }
    }

    private String prompt(String label) throws IOException {
        var console = System.console();
        if (console == null) {
            throw wrong("Interactive assignment requires a terminal.");
        }
        spec.commandLine().getErr().print(label + ": ");
        spec.commandLine().getErr().flush();
        var answer = console.readLine();
        if (answer == null) {
            throw wrong("Input ended; issue was not assigned.");
        }
        return answer.strip();
    }

    private static boolean matches(JsonObject user, String field,
            String query) {
        var value = user.get(field);
        return value != null && !value.isJsonNull()
                && value.getAsString().equalsIgnoreCase(query);
    }

    private static String safe(String value) {
        return value.replaceAll("\\p{Cntrl}", " ");
    }
}
