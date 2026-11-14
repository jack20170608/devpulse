package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.nonblank;
import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "move", aliases = "transition",
        mixinStandardHelpOptions = true,
        description = "Transition an issue by transition ID, name, or target "
                + "status; this does not move issues between projects.")
public final class JiraIssueMoveCommand extends JiraCommand.Action {
    @Parameters(index = "0", arity = "0..1", paramLabel = "KEY",
            description = "Issue key or ID; prompted when omitted.")
    String key;
    @Parameters(index = "1", arity = "0..1", paramLabel = "TARGET",
            description = "Available transition ID, transition name, "
                    + "or destination status name.")
    String target;
    @Option(names = "--comment", paramLabel = "TEXT",
            description = "Add a comment in the same transition request.")
    String comment;
    @Option(names = {"-R", "--resolution"}, paramLabel = "NAME",
            description = "Set a resolution allowed by the transition.")
    String resolution;
    @Option(names = {"-a", "--assignee"}, paramLabel = "USER",
            description = "Cloud accountId or DC username; "
                    + "me=current user, x=unassigned.")
    String assignee;
    @Option(names = "--no-input",
            description = "Never prompt; KEY and an unambiguous TARGET "
                    + "are required.")
    boolean noInput;
    @Mixin
    JiraCommand.BodyFormat bodyFormat = new JiraCommand.BodyFormat();

    @Override
    protected void execute() throws IOException {
        if ((key == null || target == null)
                && (noInput || System.console() == null)) {
            throw wrong("Supply KEY and TARGET; interactive transitions "
                    + "require a terminal.");
        }
        if (key == null) {
            key = prompt("Issue key");
        }
        nonblank(key, "KEY");
        if (target != null) {
            target = nonblank(target, "TARGET").strip();
        }
        if (comment != null) {
            nonblank(comment, "--comment");
        }
        if (resolution != null) {
            nonblank(resolution, "--resolution");
        }
        if (assignee != null) {
            nonblank(assignee, "--assignee");
            if (assignee.equals("default")) {
                throw wrong("Use an explicit assignee, me, or x; "
                        + "default assignment is not supported during transitions.");
            }
        }

        var jira = client();
        var response = jira.transitions(key, true).get("transitions");
        if (response == null || !response.isJsonArray()) {
            throw wrong("Jira returned an unexpected transitions list.");
        }
        var available = new ArrayList<JsonObject>();
        for (var value : response.getAsJsonArray()) {
            var transition = object(value);
            nonblank(string(transition, "id"), "Transition ID");
            nonblank(string(transition, "name"), "Transition name");
            available.add(transition);
        }
        if (available.isEmpty()) {
            throw wrong("No transitions are currently available; "
                    + "check issue status and Jira permissions.");
        }
        var selected = select(available);
        var metadata = selected.has("fields")
                ? object(selected.get("fields")) : new JsonObject();
        var fields = new JsonObject();
        var updates = new JsonObject();
        if (resolution != null) {
            var field = requireOperation(metadata, "resolution", "set");
            fields.add("resolution", resolution(field));
        }
        if (assignee != null) {
            requireOperation(metadata, "assignee", "set");
            if (assignee.equals("x")) {
                fields.add("assignee", JsonNull.INSTANCE);
            } else {
                var identifier = jira.cloud() ? "accountId" : "name";
                var user = assignee.equals("me")
                        ? string(jira.myself(), identifier) : assignee;
                var value = new JsonObject();
                value.addProperty(identifier, nonblank(user, "--assignee"));
                fields.add("assignee", value);
            }
        }
        if (comment != null) {
            requireOperation(metadata, "comment", "add");
            var value = new JsonObject();
            value.add("body", bodyFormat.toJira(comment, jira));
            var add = new JsonObject();
            add.add("add", value);
            var operations = new JsonArray();
            operations.add(add);
            updates.add("comment", operations);
        }
        var id = string(selected, "id");
        jira.transition(key, id, fields, updates);
        var result = new com.google.gson.JsonObject();
        result.addProperty("key", key);
        result.addProperty("transitionId", id);
        result.addProperty("changed", true);
        machineData = result;
        print("Transitioned " + key + " with ID " + id + ".");
    }

    private JsonObject select(List<JsonObject> available) throws IOException {
        if (target == null) {
            return choose(available);
        }
        var candidates = available.stream()
                .filter(value -> value.get("id").getAsString().equals(target))
                .toList();
        if (candidates.isEmpty()) {
            candidates = available.stream()
                    .filter(value -> matches(value, "name", target)).toList();
        }
        if (candidates.isEmpty()) {
            candidates = available.stream().filter(value ->
                    value.has("to") && value.get("to").isJsonObject()
                            && matches(value.getAsJsonObject("to"),
                                    "name", target)).toList();
        }
        if (candidates.isEmpty()) {
            throw wrong("No available transition matches TARGET; "
                    + "run jira transitions KEY to find an available ID.");
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (noInput || System.console() == null) {
            throw wrong("Multiple transitions match TARGET; "
                    + "use an explicit transition ID.");
        }
        return choose(candidates);
    }

    private JsonObject choose(List<JsonObject> candidates) throws IOException {
        var err = spec.commandLine().getErr();
        for (var index = 0; index < candidates.size(); index++) {
            var value = candidates.get(index);
            var destination = value.has("to") && value.get("to").isJsonObject()
                    ? string(value.getAsJsonObject("to"), "name") : "";
            err.println((index + 1) + ". " + safe(string(value, "name"))
                    + " (ID " + safe(string(value, "id")) + ")"
                    + (destination.isEmpty() ? "" : " -> " + safe(destination)));
        }
        while (true) {
            var answer = prompt("Select transition (1-" + candidates.size() + ")");
            try {
                var index = Integer.parseInt(answer) - 1;
                if (index >= 0 && index < candidates.size()) {
                    return candidates.get(index);
                }
            } catch (NumberFormatException ignored) {
                // Retry invalid selections without modifying the issue.
            }
            err.println("Enter a number from 1 to " + candidates.size() + ".");
        }
    }

    private JsonObject resolution(JsonObject metadata) throws IOException {
        if (metadata.has("allowedValues")) {
            var allowed = metadata.get("allowedValues");
            if (!allowed.isJsonArray()) {
                throw wrong("Jira returned unexpected resolution choices.");
            }
            var candidates = new ArrayList<JsonObject>();
            for (var element : allowed.getAsJsonArray()) {
                var value = object(element);
                if (matches(value, "name", resolution)
                        || matches(value, "id", resolution)) {
                    candidates.add(value);
                }
            }
            if (candidates.size() != 1) {
                throw wrong("Resolution must uniquely match a value "
                        + "allowed by this transition.");
            }
            var selected = candidates.getFirst();
            var result = new JsonObject();
            var identifier = selected.has("id") ? "id" : "name";
            result.addProperty(identifier, string(selected, identifier));
            return result;
        }
        var result = new JsonObject();
        result.addProperty("name", resolution);
        return result;
    }

    private static JsonObject requireOperation(JsonObject metadata,
            String field, String operation) throws IOException {
        if (!metadata.has(field) || !metadata.get(field).isJsonObject()) {
            throw wrong("This transition does not expose field " + field + ".");
        }
        var value = metadata.getAsJsonObject(field);
        var allowed = value.get("operations");
        if (allowed == null || !allowed.isJsonArray()
                || !allowed.getAsJsonArray().contains(new JsonPrimitive(operation))) {
            throw wrong("This transition does not allow " + operation
                    + " on field " + field + ".");
        }
        return value;
    }

    private String prompt(String label) throws IOException {
        var console = System.console();
        if (console == null) {
            throw wrong("Interactive transitions require a terminal.");
        }
        spec.commandLine().getErr().print(label + ": ");
        spec.commandLine().getErr().flush();
        var answer = console.readLine();
        if (answer == null) {
            throw wrong("Input ended; issue was not transitioned.");
        }
        return answer.strip();
    }

    private static boolean matches(JsonObject value, String field,
            String query) {
        return value.has(field) && !value.get(field).isJsonNull()
                && value.get(field).getAsString().equalsIgnoreCase(query);
    }

    private static String safe(String value) {
        return value.replaceAll("\\p{Cntrl}", " ");
    }
}
