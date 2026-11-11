package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list", mixinStandardHelpOptions = true,
        description = "Search one page of Jira issues using filters or JQL.")
public class JiraIssueListCommand extends JiraCommand.Action {
    String scopeJql;
    @Option(names = {"-w", "--watching"}, description = "Issues watched by you.")
    boolean watching;
    @Option(names = "--history", description = "Issues in your recent viewing history.")
    boolean history;
    @Option(names = {"-R", "--resolution"}, paramLabel = "NAME",
            description = "Resolution; prefix with ~ to exclude.")
    String resolution;
    @Option(names = {"-p", "--project"}, paramLabel = "KEY",
            description = "Project key; defaults to connection configuration.")
    String project;
    @Option(names = {"-s", "--status"}, paramLabel = "NAME",
            description = "Status name; prefix with ~ to exclude.")
    String status;
    @Option(names = {"-t", "--type"}, paramLabel = "NAME",
            description = "Issue type; prefix with ~ to exclude.")
    String type;
    @Option(names = {"-y", "--priority"}, paramLabel = "NAME",
            description = "Priority name; prefix with ~ to exclude.")
    String priority;
    @Option(names = {"-a", "--assignee"}, paramLabel = "USER",
            description = "Cloud accountId or DC username; "
                    + "me=current user, x=unassigned, ~x=assigned.")
    String assignee;
    @Option(names = {"-r", "--reporter"}, paramLabel = "USER",
            description = "Cloud accountId or DC username; me=current user.")
    String reporter;
    @Option(names = {"-l", "--label"}, paramLabel = "LABEL",
            description = "Repeat to require every label.")
    List<String> labels;
    @Option(names = "--created", paramLabel = "DATE",
            description = "Created on or after a Jira date or relative "
                    + "time, e.g. -7d.")
    String created;
    @Option(names = "--updated", paramLabel = "DATE",
            description = "Updated on or after a Jira date or relative time.")
    String updated;
    @Option(names = "--created-before", paramLabel = "DATE")
    String createdBefore;
    @Option(names = "--updated-before", paramLabel = "DATE")
    String updatedBefore;
    @Option(names = "--columns", split = ",", paramLabel = "COLUMN,...",
            description = "key,type,status,priority,assignee,summary,reporter,"
                    + "created,updated,resolution,labels,components,fixVersions,parent,epic.")
    List<String> columns;
    @Option(names = {"-q", "--jql"}, paramLabel = "JQL",
            description = "Complete JQL, including optional ORDER BY; "
                    + "cannot be combined with filters or sort flags.")
    String jql;
    @Option(names = "--order-by", paramLabel = "FIELD",
            description = "created (default), updated, priority, key, "
                    + "rank, summary, status, assignee, or issuetype.")
    String orderBy;
    @Option(names = "--reverse",
            description = "Sort ascending instead of descending.")
    boolean reverse;
    @Option(names = "--limit", defaultValue = "50", paramLabel = "N",
            description = "Maximum issues in this page, 1-100 (default: 50).")
    int limit;
    @Option(names = "--start-at", paramLabel = "OFFSET",
            description = "Data Center only: zero-based result offset.")
    Integer startAt;
    @Option(names = "--next-page-token", paramLabel = "TOKEN",
            description = "Cloud only: continuation token from a prior page.")
    String nextPageToken;
    @Option(names = {"--raw", "--json"},
            description = "Output the Jira JSON page, including pagination.")
    boolean raw;
    @Option(names = "--plain",
            description = "Output tab-separated rows instead of a table.")
    boolean plain;
    @Option(names = "--csv",
            description = "List recent issues in CSV format.")
    boolean csv;
    @Option(names = "--no-headers",
            description = "Omit column headers from table/plain/CSV output.")
    boolean noHeaders;

    @Override
    protected void execute() throws IOException {
        if (raw && (plain || csv || noHeaders)) {
            throw wrong("--raw/--json cannot be combined with "
                    + "--plain, --csv, or --no-headers.");
        }
        if (csv && plain) {
            throw wrong("--csv cannot be combined with --plain.");
        }
        var selectedColumns = columns == null
                ? List.of("key", "type", "status", "priority", "assignee", "epic", "summary")
                : columns.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        for (var column : selectedColumns) {
            if (!List.of("key", "type", "status", "priority", "assignee", "summary",
                    "reporter", "created", "updated", "resolution", "labels",
                    "components", "fixversions", "parent", "epic").contains(column)) {
                throw wrong("Unsupported --columns value; see list --help.");
            }
        }
        var query = query();
        var jira = client();
        var epicField = (raw || selectedColumns.contains("epic")) && !jira.cloud()
                ? JiraAgileCommands.epicField(jira, "Epic Link",
                        "com.pyxis.greenhopper.jira:gh-epic-link") : null;
        var requested = new ArrayList<>(raw
                ? List.of("summary", "status", "issuetype", "priority",
                        "assignee", "reporter", "created", "updated", "resolution",
                        "labels", "components", "fixVersions", "parent")
                : selectedColumns.stream().filter(column -> !column.equals("key"))
                .map(column -> switch (column) {
                    case "type" -> "issuetype";
                    case "fixversions" -> "fixVersions";
                    case "epic" -> "parent";
                    default -> column;
                }).distinct().toList());
        if (epicField != null) {
            requested.add(epicField);
        }
        var page = jira.search(query, limit, startAt, nextPageToken, requested);
        var issues = page.get("issues");
        if (issues == null || !issues.isJsonArray()) {
            throw wrong("Jira search response is missing an issues array.");
        }
        if (raw) {
            print(page.toString());
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (var value : issues.getAsJsonArray()) {
            var issue = object(value);
            var fields = object(issue.get("fields"));
            var row = new ArrayList<String>();
            for (var column : selectedColumns) {
                row.add(column(issue, fields, column, epicField));
            }
            rows.add(row);
        }
        var hint = pageHint(page, jira.cloud(), rows.size());
        var headers = selectedColumns.stream()
                .map(value -> value.toUpperCase(Locale.ROOT)).toList();
        var widths = new int[headers.size()];
        for (var i = 0; i < widths.length; i++) {
            widths[i] = headers.get(i).length();
            for (var row : rows) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        if (!noHeaders) {
            print(row(headers, widths));
        }
        for (var values : rows) {
            print(row(values, widths));
        }
        if (hint != null) {
            spec.commandLine().getErr().println(hint);
        }
    }

    private String query() throws IOException {
        var clauses = new ArrayList<String>();
        var selectedProject = project == null && jql == null
                ? configuration().project() : project;
        filter(clauses, "project", selectedProject, false);
        filter(clauses, "status", status, false);
        filter(clauses, "issuetype", type, false);
        filter(clauses, "priority", priority, false);
        filter(clauses, "assignee", assignee, true);
        filter(clauses, "reporter", reporter, true);
        filter(clauses, "resolution", resolution, false);
        if (watching) {
            clauses.add("watcher = currentUser()");
        }
        if (history) {
            clauses.add("issuekey IN issueHistory()");
        }
        if (labels != null) {
            for (var label : labels) {
                filter(clauses, "labels", label, false);
            }
        }
        if (created != null) {
            clauses.add("created >= " + date(created));
        }
        if (updated != null) {
            clauses.add("updated >= " + date(updated));
        }
        if (createdBefore != null) {
            clauses.add("created < " + date(createdBefore));
        }
        if (updatedBefore != null) {
            clauses.add("updated < " + date(updatedBefore));
        }
        if (jql != null) {
            if (jql.isBlank()) {
                throw wrong("--jql must not be blank.");
            }
            if (!clauses.isEmpty() || orderBy != null || reverse) {
                throw wrong("--jql is a complete query; do not combine "
                        + "it with filters, --order-by, or --reverse.");
            }
            return scopeJql == null ? jql : scoped(jql, scopeJql);
        }
        var sort = orderBy == null ? "created"
                : orderBy.toLowerCase(Locale.ROOT);
        if (!List.of("created", "updated", "priority", "key", "rank",
                "summary", "status", "assignee", "issuetype").contains(sort)) {
            throw wrong("Unsupported --order-by field; see list --help.");
        }
        // Cloud enhanced search requires a restriction, not only ORDER BY.
        if (scopeJql != null) {
            clauses.add("(" + scopeJql + ")");
        }
        if (clauses.isEmpty()) {
            clauses.add("created >= \"-30d\"");
        }

        return String.join(" AND ", clauses) + " ORDER BY " + sort
                + (reverse ? " ASC" : " DESC");
    }

    private static String date(String value) throws IOException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "day", "today" -> "startOfDay()";
            case "week" -> "startOfWeek()";
            case "month" -> "startOfMonth()";
            case "year" -> "startOfYear()";
            default -> quote(value);
        };
    }

    private static String scoped(String query, String scope) throws IOException {
        boolean quoted = false;
        char delimiter = 0;
        for (var i = 0; i < query.length(); i++) {
            var character = query.charAt(i);
            if (character == '\\') {
                i++;
            } else if (quoted && character == delimiter) {
                quoted = false;
            } else if (!quoted && (character == '"' || character == '\'')) {
                quoted = true;
                delimiter = character;
            } else if (!quoted && (i == 0 || Character.isWhitespace(query.charAt(i - 1)))
                    && query.substring(i).matches("(?is)ORDER\\s+BY\\b.*")) {
                return "(" + query.substring(0, i).strip() + ") AND (" + scope
                        + ") " + query.substring(i);
            }
        }
        if (quoted) {
            throw wrong("Unterminated quoted JQL value.");
        }
        return "(" + query + ") AND (" + scope + ")";
    }

    private String column(JsonObject issue, JsonObject fields, String column,
            String epicField)
            throws IOException {
        return switch (column) {
            case "key" -> scalar(issue.get("key"), csv);
            case "type" -> named(fields, "issuetype", "name");
            case "assignee", "reporter" -> named(fields, column, "displayName");
            case "status", "priority", "resolution" -> named(fields, column, "name");
            case "parent" -> named(fields, "parent", "key");
            case "epic" -> epic(fields, epicField);
            case "labels", "components", "fixversions" -> {
                var value = fields.get(column.equals("fixversions") ? "fixVersions" : column);
                var values = new ArrayList<String>();
                if (value != null && !value.isJsonNull()) {
                    if (!value.isJsonArray()) {
                        throw wrong("Unexpected Jira list field shape.");
                    }
                    for (var item : value.getAsJsonArray()) {
                        values.add(column.equals("labels") ? scalar(item, csv)
                                : scalar(object(item).get("name"), csv));
                    }
                }
                yield values.isEmpty() ? "-" : String.join(", ", values);
            }
            default -> scalar(fields.get(column), csv);
        };
    }

    private String epic(JsonObject fields, String epicField) throws IOException {
        if (epicField != null) {
            var link = fields.get(epicField);
            if (link != null && !link.isJsonNull()) {
                return scalar(link, csv);
            }
        }
        var parent = fields.get("parent");
        if (parent == null || parent.isJsonNull()) {
            return "-";
        }
        var parentFields = object(parent).get("fields");
        if (parentFields == null || parentFields.isJsonNull()) {
            return "-";
        }
        var type = object(parentFields).get("issuetype");
        if (type == null || type.isJsonNull()) {
            return "-";
        }
        var issueType = object(type);
        var level = issueType.get("hierarchyLevel");
        var name = issueType.get("name");
        var isEpic = level != null && !level.isJsonNull()
                ? level.isJsonPrimitive() && level.getAsJsonPrimitive().isNumber()
                        && level.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0
                : name != null && name.isJsonPrimitive()
                        && "Epic".equalsIgnoreCase(name.getAsString());
        return isEpic ? scalar(object(parent).get("key"), csv) : "-";
    }

    private static void filter(List<String> clauses, String field,
            String value, boolean user) throws IOException {
        if (value == null) {
            return;
        }
        var exclude = value.startsWith("~");
        var term = exclude ? value.substring(1) : value;
        if (user && term.equals("x")) {
            clauses.add(field + (exclude ? " IS NOT EMPTY" : " IS EMPTY"));
        } else {
            var expression = user && term.equals("me")
                    ? "currentUser()" : quote(term);
            clauses.add(field + (exclude ? " != " : " = ") + expression);
        }
    }

    private static String quote(String value) throws IOException {
        if (value.isBlank() || value.codePoints()
                .anyMatch(Character::isISOControl)) {
            throw wrong("Search filter values must be nonblank "
                    + "and contain no control characters.");
        }
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private String row(List<String> values, int[] widths) {
        if (csv) {
            return String.join(",", values.stream()
                    .map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                    .toList());
        }
        if (plain) {
            return String.join("\t", values);
        }
        var result = new StringBuilder();
        for (var i = 0; i < values.size(); i++) {
            result.append(values.get(i));
            if (i < values.size() - 1) {
                result.append(" ".repeat(widths[i]
                        - values.get(i).length() + 2));
            }
        }
        return result.toString();
    }

    private static String pageHint(JsonObject page, boolean cloud,
            int count) throws IOException {
        if (cloud) {
            var token = page.get("nextPageToken");
            if (token != null && !token.isJsonNull()
                    && !scalar(token).isBlank()) {
                return "Next page: repeat the same query with "
                        + "--next-page-token " + token;
            }
        } else {
            var offset = page.get("startAt");
            var total = page.get("total");
            if (offset == null || total == null) {
                throw wrong("Jira search response is missing "
                        + "startAt or total.");
            }
            try {
                var next = Math.addExact(number(offset), count);
                if (count > 0 && next < number(total)) {
                    return "Next page: repeat the same query with "
                            + "--start-at " + next;
                }
            } catch (NumberFormatException | ArithmeticException exception) {
                throw wrong("Invalid Jira pagination numbers.");
            }
        }
        return null;
    }

    private static long number(JsonElement value) throws IOException {
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw wrong("Invalid Jira pagination numbers.");
        }
        var result = value.getAsBigDecimal().longValueExact();
        if (result < 0) {
            throw wrong("Invalid Jira pagination numbers.");
        }
        return result;
    }

    private String named(JsonObject fields, String name,
            String member) throws IOException {
        var value = fields.get(name);
        return value == null || value.isJsonNull()
                ? "-" : scalar(object(value).get(member), csv);
    }

    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw wrong("Unexpected Jira search response shape.");
        }
        return value.getAsJsonObject();
    }

    private static String scalar(JsonElement value) throws IOException {
        return scalar(value, false);
    }

    private static String scalar(JsonElement value, boolean preserveControls)
            throws IOException {
        if (value == null || value.isJsonNull()) {
            return "-";
        }
        if (!value.isJsonPrimitive()) {
            throw wrong("Unexpected Jira search field shape.");
        }
        var text = value.getAsString();
        return preserveControls ? text
                : text.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
    }
}
