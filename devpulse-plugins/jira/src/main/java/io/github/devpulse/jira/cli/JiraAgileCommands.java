package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCatalogCommands.array;
import static io.github.devpulse.jira.cli.JiraCatalogCommands.encode;
import static io.github.devpulse.jira.cli.JiraCatalogCommands.issueId;
import static io.github.devpulse.jira.cli.JiraCatalogCommands.numeric;
import static io.github.devpulse.jira.cli.JiraCatalogCommands.values;
import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.devpulse.jira.JiraClient;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public final class JiraAgileCommands {
    private JiraAgileCommands() {
    }

    @Command(name = "epic", mixinStandardHelpOptions = true,
            subcommands = {EpicList.class, EpicCreate.class,
                    EpicAdd.class, EpicRemove.class},
            description = "List, create, and manage Jira epics.")
    public static final class Epic {
    }

    @Command(name = "sprint", mixinStandardHelpOptions = true,
            subcommands = {SprintList.class, SprintAdd.class},
            description = "List Jira Software sprints and manage membership.")
    public static final class Sprint {
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "Search epics, or issues in an epic, using issue filters.")
    public static final class EpicList extends JiraIssueListCommand {
        @Option(names = "--table", description = "Print a table (default).")
        boolean table;
        @Parameters(index = "0", arity = "0..1", paramLabel = "EPIC_KEY",
                description = "Epic key or ID; omit to list epics.")
        String epic;

        @Override
        protected void execute() throws IOException {
            if (type != null) {
                throw wrong("epic list does not accept --type.");
            }
            if (epic == null) {
                scopeJql = "issuetype = \"Epic\"";
            } else {
                issueId(epic);
                var jira = client();
                if (jira.cloud()) {
                    scopeJql = "parent = " + quote(epic);
                } else {
                    var field = epicField(jira, "Epic Link",
                            "com.pyxis.greenhopper.jira:gh-epic-link");
                    if (field == null) {
                        throw wrong("Jira field metadata does not expose "
                                + "an Epic Link field.");
                    }
                    scopeJql = "cf[" + field.substring("customfield_".length())
                            + "] = " + quote(epic);
                }
            }
            super.execute();
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true,
            description = "Create an Epic using the ordinary issue options.")
    public static final class EpicCreate extends JiraCreateCommand {
        @Option(names = {"-n", "--name"}, paramLabel = "TEXT",
                description = "Epic name; prompts when a terminal is available.")
        String name;

        @Override
        protected void execute() throws IOException {
            if (type != null && !type.equalsIgnoreCase("Epic")) {
                throw wrong("epic create always creates an Epic.");
            }
            type = "Epic";
            name = required(this, name, "Epic name", "--name", noInput);
            var jira = client();
            var field = epicField(jira, "Epic Name",
                    "com.pyxis.greenhopper.jira:gh-epic-label");
            if (field == null) {
                if (!jira.cloud()) {
                    throw wrong("Jira field metadata does not expose "
                            + "an Epic Name field.");
                }
                // New Cloud hierarchies use summary as the Epic's name.
                if (summary != null && !summary.equals(name)) {
                    throw wrong("This Cloud project has no Epic Name field; "
                            + "--name must equal --summary, or omit --summary.");
                }
                if (summary == null && file != null) {
                    var fields = JiraCommand.fields(JiraCommand.load(file));
                    if (fields.has("summary") && !fields.get("summary").isJsonNull()
                            && !name.equals(string(fields, "summary"))) {
                        throw wrong("This Cloud project has no Epic Name field; "
                                + "--name must match the template summary.");
                    }
                }
                summary = name;
            } else {
                custom = custom == null ? new ArrayList<>()
                        : new ArrayList<>(custom);
                var prefix = field + "=";
                if (custom.stream().anyMatch(value -> value.startsWith(prefix))) {
                    throw wrong("Use --name, not --custom, for Epic Name.");
                }
                custom.add(prefix + new JsonPrimitive(name));
            }
            super.execute();
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Move up to 50 issues into an Epic.")
    public static final class EpicAdd extends Membership {
        @Parameters(index = "0", arity = "0..1", paramLabel = "EPIC_KEY")
        String epic;
        @Parameters(index = "1..*", arity = "0..*", paramLabel = "ISSUE")
        List<String> issues;

        @Override
        protected void execute() throws IOException {
            epic = issueId(required(this, epic,
                    "Epic key or ID", "EPIC_KEY", noInput));
            var selected = issues(this, issues, noInput);
            client().agile("POST", "epic/" + encode(epic) + "/issue",
                    membership(selected));
            print("Added " + selected.size() + " issue(s) to Epic " + epic + ".");
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true,
            description = "Remove up to 50 issues from their Epics.")
    public static final class EpicRemove extends Membership {
        @Parameters(index = "0..*", arity = "0..*", paramLabel = "ISSUE")
        List<String> issues;

        @Override
        protected void execute() throws IOException {
            var selected = issues(this, issues, noInput);
            client().agile("POST", "epic/none/issue", membership(selected));
            print("Removed " + selected.size() + " issue(s) from their Epics.");
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Move up to 50 issues into an active or future sprint.")
    public static final class SprintAdd extends Membership {
        @Parameters(index = "0", arity = "0..1", paramLabel = "SPRINT_ID")
        String sprint;
        @Parameters(index = "1..*", arity = "0..*", paramLabel = "ISSUE")
        List<String> issues;

        @Override
        protected void execute() throws IOException {
            sprint = numeric(required(this, sprint,
                    "Sprint ID", "SPRINT_ID", noInput), "SPRINT_ID");
            var selected = issues(this, issues, noInput);
            client().agile("POST", "sprint/" + sprint + "/issue",
                    membership(selected));
            print("Added " + selected.size() + " issue(s) to sprint "
                    + sprint + ".");
        }
    }

    abstract static class Membership extends JiraCommand.Action {
        @Option(names = "--no-input",
                description = "Never prompt; missing arguments fail.")
        boolean noInput;
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List 25 recently created sprints, or search sprint issues. "
                    + "Metadata pages use local offsets after fetching all board sprints. "
                    + "Sprint columns: id,name,state,startDate,endDate,completeDate,goal. "
                    + "The default metadata limit is 25; the issue limit is 50.")
    public static final class SprintList extends JiraIssueListCommand {
        @Option(names = "--table", description = "Print a table (default).")
        boolean table;
        @Parameters(index = "0", arity = "0..1", paramLabel = "SPRINT_ID",
                description = "Search issues in this sprint, without board discovery.")
        String sprint;
        @Option(names = {"--board-id", "--board"}, paramLabel = "ID",
                description = "Scrum board ID; otherwise resolve the project "
                        + "only when exactly one Scrum board matches.")
        String board;
        @Option(names = "--state", split = ",", paramLabel = "STATE",
                description = "Sprint states: future, active, closed.")
        List<String> states;
        @Option(names = "--current",
                description = "Search issues in the unique active sprint.")
        boolean current;
        @Option(names = "--prev",
                description = "Search issues in the most recently completed sprint.")
        boolean previous;
        @Option(names = "--next",
                description = "Search issues in the unique future sprint.")
        boolean following;

        @Override
        protected void execute() throws IOException {
            var selectors = (current ? 1 : 0) + (previous ? 1 : 0)
                    + (following ? 1 : 0);
            if (selectors > 1 || (sprint != null && selectors > 0)) {
                throw wrong("Use one SPRINT_ID, --current, --prev, or --next.");
            }
            if (states != null && (sprint != null || selectors > 0)) {
                throw wrong("--state filters the sprint catalog; "
                        + "do not combine it with a sprint issue selector.");
            }
            if (sprint != null) {
                if (board != null) {
                    numeric(board, "--board-id");
                }
                searchSprint(sprint);
                return;
            }
            if (selectors == 0) {
                validateMetadataOptions();
            }
            String state = current ? "active" : previous ? "closed"
                    : following ? "future" : states();
            var jira = client();
            var selectedBoard = board == null
                    ? discoverBoard(jira) : numeric(board, "--board-id");
            var sprints = allSprints(jira, selectedBoard, state);
            if (selectors > 0) {
                searchSprint(selectSprint(sprints));
                return;
            }
            if (!spec.commandLine().getParseResult().hasMatchedOption("--limit")) {
                limit = 25;
            }
            var offset = startAt == null ? 0 : startAt;
            JiraCatalogCommands.bounds(offset, limit);
            var sorted = new ArrayList<JsonObject>();
            for (var value : sprints) {
                var sprintObject = object(value);
                numeric(string(sprintObject, "id"), "Jira sprint ID");
                sorted.add(sprintObject);
            }
            sorted.sort(Comparator.comparing((JsonObject value) ->
                    new BigInteger(value.get("id").getAsString())).reversed());
            var all = new JsonArray();
            sorted.forEach(all::add);
            var page = JiraCatalogCommands.slice(all, offset, limit);
            page.addProperty("boardId", selectedBoard);
            page.addProperty("order", "id DESC");
            if (raw) {
                print(page.toString());
            } else {
                JiraCatalogCommands.render(this, values(page),
                        List.of("id", "name", "state", "startDate", "endDate",
                                "completeDate", "goal"),
                        selectedColumns(), plain, noHeaders);
            }
            JiraCatalogCommands.continuation(this, page, values(page).size());
        }

        private void searchSprint(String id) throws IOException {
            scopeJql = "sprint = " + numeric(id, "SPRINT_ID");
            super.execute();
        }

        private String states() throws IOException {
            if (states == null) {
                return null;
            }
            if (states.isEmpty() || states.stream().anyMatch(value ->
                    !Set.of("future", "active", "closed").contains(value))) {
                throw wrong("--state must contain future, active, or closed.");
            }
            return String.join(",", new java.util.LinkedHashSet<>(states));
        }

        private void validateMetadataOptions() throws IOException {
            var allowed = Set.of("--project", "--board-id", "--state",
                    "--limit", "--start-at", "--raw", "--plain", "--no-headers",
                    "--table", "--columns");
            for (var option : spec.commandLine().getParseResult()
                    .matchedOptions()) {
                if (!allowed.contains(option.longestName())) {
                    // Alias order does not determine the canonical option name.
                    if (java.util.Arrays.stream(option.names())
                            .noneMatch(allowed::contains)) {
                        throw wrong("Issue filters require SPRINT_ID, "
                                + "--current, --prev, or --next.");
                    }
                }
            }
            if (raw && (plain || noHeaders || selectedColumns() != null)) {
                throw wrong("--raw/--json cannot be combined with --plain, "
                        + "--no-headers, or --columns.");
            }
            JiraCatalogCommands.bounds(startAt == null ? 0 : startAt, limit);
        }

        private List<String> selectedColumns() {
            return columns;
        }

        private String discoverBoard(JiraClient jira) throws IOException {
            var selectedProject = JiraCatalogCommands.project(
                    project, configuration().project());
            int offset = 0;
            var found = new HashSet<String>();
            while (true) {
                var page = object(jira.agile("GET",
                        "board?type=scrum&projectKeyOrId="
                                + encode(selectedProject)
                                + "&startAt=" + offset + "&maxResults=100",
                        null));
                requireOffset(page, offset);
                var boards = values(page);
                for (var item : boards) {
                    var candidate = object(item);
                    found.add(numeric(string(candidate, "id"),
                            "Jira board ID"));
                }
                if (found.size() > 1) {
                    throw wrong("Multiple Scrum boards match this project; "
                            + "select one with --board-id (see board list).");
                }
                var next = JiraCatalogCommands.next(page, boards.size());
                if (next < 0) {
                    break;
                }
                offset = next;
            }
            if (found.isEmpty()) {
                throw wrong("No Scrum board found for this project; "
                        + "supply --board-id explicitly.");
            }
            return found.iterator().next();
        }

        private String selectSprint(JsonArray sprints) throws IOException {
            if (sprints.isEmpty()) {
                throw wrong("No matching sprint; specify a SPRINT_ID.");
            }
            if (!previous) {
                if (sprints.size() != 1) {
                    throw wrong("Multiple matching "
                            + (current ? "active" : "future")
                            + " sprints; specify a SPRINT_ID.");
                }
                return string(object(sprints.get(0)), "id");
            }
            Instant latest = null;
            String result = null;
            boolean ambiguous = false;
            for (var value : sprints) {
                var item = object(value);
                var completed = completion(item);
                if (latest == null || completed.isAfter(latest)) {
                    latest = completed;
                    result = string(item, "id");
                    ambiguous = false;
                } else if (completed.equals(latest)) {
                    ambiguous = true;
                }
            }
            if (ambiguous) {
                throw wrong("Previous sprint is ambiguous; specify a SPRINT_ID.");
            }
            return result;
        }
    }

    private static JsonArray allSprints(JiraClient jira, String board,
            String state) throws IOException {
        var all = new JsonArray();
        var seen = new HashSet<String>();
        int offset = 0;
        while (true) {
            var page = object(jira.agile("GET", "board/" + board
                    + "/sprint?startAt=" + offset + "&maxResults=100"
                    + (state == null ? "" : "&state=" + encode(state)), null));
            requireOffset(page, offset);
            var batch = values(page);
            for (var value : batch) {
                var item = object(value);
                var id = numeric(string(item, "id"), "Jira sprint ID");
                if (!seen.add(id)) {
                    throw wrong("Sprint pages overlap; rerun the command "
                            + "after board changes have settled.");
                }
                all.add(item);
            }
            var next = JiraCatalogCommands.next(page, batch.size());
            if (next < 0) {
                return all;
            }
            offset = next;
        }
    }

    private static void requireOffset(JsonObject page, int requested)
            throws IOException {
        if (JiraCatalogCommands.integer(page, "startAt") != requested) {
            throw wrong("Jira returned an unexpected pagination offset.");
        }
    }

    private static Instant completion(JsonObject sprint) throws IOException {
        var field = sprint.has("completeDate") ? "completeDate" : "endDate";
        try {
            return OffsetDateTime.parse(string(sprint, field)).toInstant();
        } catch (DateTimeParseException exception) {
            throw wrong("Closed sprint has no valid completion date; "
                    + "specify a SPRINT_ID.");
        }
    }

    static String epicField(JiraClient jira, String name,
            String schemaKey) throws IOException {
        return epicField(array(jira.request("GET", "field", null)), name, schemaKey);
    }

    static String epicField(JsonArray metadata, String name,
            String schemaKey) throws IOException {
        var schemaMatches = new HashSet<String>();
        var nameMatches = new HashSet<String>();
        for (var value : metadata) {
            var field = object(value);
            var id = string(field, "id");
            if (!id.matches("customfield_[0-9]+")) {
                continue;
            }
            if (field.has("schema") && field.get("schema").isJsonObject()) {
                var schema = field.getAsJsonObject("schema");
                if (schema.has("custom") && !schema.get("custom").isJsonNull()
                        && schemaKey.equals(schema.get("custom").getAsString())) {
                    schemaMatches.add(id);
                }
            }
            if (field.has("name") && !field.get("name").isJsonNull()
                    && name.equalsIgnoreCase(field.get("name").getAsString())) {
                nameMatches.add(id);
            }
        }
        var matches = schemaMatches.isEmpty() ? nameMatches : schemaMatches;
        if (matches.size() > 1) {
            throw wrong("Jira field metadata has ambiguous " + name + " fields.");
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    private static JsonObject membership(List<String> issues) {
        var result = new JsonObject();
        var values = new JsonArray();
        issues.forEach(values::add);
        result.add("issues", values);
        return result;
    }

    private static List<String> issues(JiraCommand.Action action,
            List<String> supplied, boolean noInput) throws IOException {
        var selected = supplied;
        if (selected == null || selected.isEmpty()) {
            selected = List.of(required(action, null,
                    "Issue keys or IDs (space-separated)", "ISSUE", noInput)
                    .strip().split("\\s+"));
        }
        if (selected.isEmpty() || selected.size() > 50) {
            throw wrong("Supply between 1 and 50 issues.");
        }
        var seen = new HashSet<String>();
        for (var issue : selected) {
            issueId(issue);
            if (!seen.add(issue)) {
                throw wrong("Do not supply duplicate issues.");
            }
        }
        return selected;
    }

    private static String required(JiraCommand.Action action, String value,
            String label, String flag, boolean noInput) throws IOException {
        if (value == null) {
            var console = System.console();
            if (noInput || console == null) {
                throw wrong("Supply " + flag
                        + "; interactive prompts require a terminal.");
            }
            action.spec.commandLine().getErr().print(label + ": ");
            action.spec.commandLine().getErr().flush();
            value = console.readLine();
        }
        if (value == null || value.isBlank()
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw wrong(flag + " must be nonblank and contain no controls.");
        }
        return value;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
