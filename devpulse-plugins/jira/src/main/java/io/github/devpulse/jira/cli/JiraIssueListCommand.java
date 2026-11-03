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
public final class JiraIssueListCommand extends JiraCommand.Action {
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
        var query = query();
        var jira = client();
        var page = jira.search(query, limit, startAt, nextPageToken);
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
            rows.add(List.of(scalar(issue.get("key"), csv),
                    named(fields, "issuetype", "name"),
                    named(fields, "status", "name"),
                    named(fields, "priority", "name"),
                    named(fields, "assignee", "displayName"),
                    scalar(fields.get("summary"), csv)));
        }
        var hint = pageHint(page, jira.cloud(), rows.size());
        var headers = List.of("KEY", "TYPE", "STATUS", "PRIORITY",
                "ASSIGNEE", "SUMMARY");
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
        if (labels != null) {
            for (var label : labels) {
                filter(clauses, "labels", label, false);
            }
        }
        if (created != null) {
            clauses.add("created >= " + quote(created));
        }
        if (updated != null) {
            clauses.add("updated >= " + quote(updated));
        }
        if (jql != null) {
            if (jql.isBlank()) {
                throw wrong("--jql must not be blank.");
            }
            if (!clauses.isEmpty() || orderBy != null || reverse) {
                throw wrong("--jql is a complete query; do not combine "
                        + "it with filters, --order-by, or --reverse.");
            }
            return jql;
        }
        var sort = orderBy == null ? "created"
                : orderBy.toLowerCase(Locale.ROOT);
        if (!List.of("created", "updated", "priority", "key", "rank",
                "summary", "status", "assignee", "issuetype").contains(sort)) {
            throw wrong("Unsupported --order-by field; see list --help.");
        }
        // Cloud enhanced search requires a restriction, not only ORDER BY.
        if (clauses.isEmpty()) {
            clauses.add("created >= \"-30d\"");
        }
        return String.join(" AND ", clauses) + " ORDER BY " + sort
                + (reverse ? " ASC" : " DESC");
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
