package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.object;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraConfiguration;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public final class JiraCatalogCommands {
    private JiraCatalogCommands() {
    }

    @Command(name = "project", mixinStandardHelpOptions = true,
            subcommands = ProjectList.class,
            description = "List accessible Jira projects.")
    public static final class Project {
    }

    @Command(name = "board", mixinStandardHelpOptions = true,
            subcommands = BoardList.class,
            description = "List Jira Software boards.")
    public static final class Board {
    }

    @Command(name = "release", mixinStandardHelpOptions = true,
            subcommands = ReleaseList.class,
            description = "List project releases (versions).")
    public static final class Release {
    }

    abstract static class CatalogList extends JiraCommand.Action {
        @Option(names = "--limit", defaultValue = "50",
                description = "Maximum rows per page, 1-100.")
        int limit;
        @Option(names = "--start-at", defaultValue = "0",
                description = "Zero-based continuation offset.")
        int startAt;
        @Option(names = {"--raw", "--json"},
                description = "Print JSON including pagination metadata.")
        boolean raw;
        @Option(names = "--plain", description = "Print tab-separated rows.")
        boolean plain;
        @Option(names = "--no-headers", description = "Omit table headers.")
        boolean noHeaders;
        @Option(names = "--table", description = "Print a table (default).")
        boolean table;
        @Option(names = "--columns", split = ",",
                description = "Comma-separated output column names.")
        List<String> columns;

        void validate() throws IOException {
            bounds(startAt, limit);
            if (raw && (plain || noHeaders || columns != null)) {
                throw wrong("--raw/--json cannot be combined with "
                        + "--plain, --no-headers, or --columns.");
            }
        }

        void show(JsonObject page, List<String> headers) throws IOException {
            var values = values(page);
            if (raw) {
                print(page.toString());
            } else {
                render(this, values, headers, columns, plain, noHeaders);
            }
            continuation(this, page, values.size());
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List accessible projects, one explicit page.")
    public static final class ProjectList extends CatalogList {
        @Override
        protected void execute() throws IOException {
            validate();
            var jira = client();
            JsonObject page;
            if (jira.cloud()) {
                page = object(jira.request("GET", "project/search?startAt="
                        + startAt + "&maxResults=" + limit, null));
            } else {
                page = slice(array(jira.request("GET", "project", null)),
                        startAt, limit);
            }
            show(page, List.of("key", "name", "id"));
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List Agile boards in the configured project.")
    public static final class BoardList extends CatalogList {
        @Option(names = {"-p", "--project"}, paramLabel = "KEY_OR_ID",
                description = "Project key or ID; defaults to configuration.")
        String project;
        @Option(names = "--type", paramLabel = "scrum|kanban|simple",
                description = "Restrict board type.")
        String type;
        @Override
        protected void execute() throws IOException {
            validate();
            var selected = project(project, configuration().project());
            if (type != null
                    && !List.of("scrum", "kanban", "simple").contains(type)) {
                throw wrong("--type must be scrum, kanban, or simple.");
            }
            var page = object(client().agile("GET",
                    "board?projectKeyOrId=" + encode(selected)
                    + "&startAt=" + startAt + "&maxResults=" + limit
                    + (type == null ? "" : "&type=" + type), null));
            show(page, List.of("id", "name", "type"));
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List project releases, one explicit page.")
    public static final class ReleaseList extends CatalogList {
        @Option(names = {"-p", "--project"}, paramLabel = "KEY_OR_ID",
                description = "Project key or ID; defaults to configuration.")
        String project;
        @Override
        protected void execute() throws IOException {
            validate();
            var selected = project(project, configuration().project());
            var jira = client();
            JsonObject page;
            if (jira.cloud()) {
                page = object(jira.request("GET", "project/" + encode(selected)
                        + "/version?startAt=" + startAt
                        + "&maxResults=" + limit, null));
            } else {
                page = slice(array(jira.request("GET", "project/"
                        + encode(selected) + "/versions", null)),
                        startAt, limit);
            }
            show(page, List.of("id", "name", "released", "archived",
                    "releaseDate"));
        }
    }

    @Command(name = "open", mixinStandardHelpOptions = true,
            description = "Open an issue or configured project in a browser.")
    public static final class Open extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY",
                description = "Issue key or numeric issue ID.")
        String key;
        @Option(names = {"--print", "--print-url"},
                description = "Only print the destination URL.")
        boolean printUrl;
        @Override
        protected void execute() throws IOException {
            var config = configuration();
            var base = JiraConfiguration.validateUrl(config.url());
            var destination = key == null
                    ? base.resolve("projects/"
                            + encode(project(null, config.project())))
                    : base.resolve("browse/" + encode(issueId(key)));
            if (!printUrl) {
                try {
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop()
                                    .isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(destination);
                        return;
                    }
                } catch (IOException | UnsupportedOperationException
                        | SecurityException exception) {
                    // Headless hosts still provide a usable destination.
                }
                spec.commandLine().getErr().println(
                        "Browser unavailable; open this URL manually:");
            }
            print(destination.toASCIIString());
        }
    }

    static String project(String explicit, String configured)
            throws IOException {
        var value = explicit == null ? configured : explicit;
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]*|[0-9]+")) {
            throw wrong("A project key or numeric ID is required; "
                    + "use --project or configure project.");
        }
        return value;
    }

    static String issueId(String value) throws IOException {
        if (value == null || !value.matches(
                "[A-Za-z][A-Za-z0-9_]*-[0-9]+|[0-9]+")) {
            throw wrong("Expected a Jira issue key or numeric issue ID.");
        }
        return value;
    }

    static String numeric(String value, String label) throws IOException {
        if (value == null || !value.matches("[0-9]+")
                || value.chars().allMatch(c -> c == '0')) {
            throw wrong(label + " must be a positive numeric ID.");
        }
        return value;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    static void bounds(int start, int limit) throws IOException {
        if (start < 0 || limit < 1 || limit > 100) {
            throw wrong("--start-at must be nonnegative and "
                    + "--limit must be between 1 and 100.");
        }
    }

    static JsonArray array(JsonElement value) throws IOException {
        if (value == null || !value.isJsonArray()) {
            throw wrong("Jira response is missing an expected array.");
        }
        return value.getAsJsonArray();
    }

    static JsonArray values(JsonObject page) throws IOException {
        return array(page.get("values"));
    }

    static JsonObject slice(JsonArray all, int offset, int limit) {
        var page = new JsonObject();
        var rows = new JsonArray();
        for (long i = offset; i < Math.min((long) offset + limit, all.size());
                i++) {
            rows.add(all.get((int) i));
        }
        page.add("values", rows);
        page.addProperty("startAt", offset);
        page.addProperty("maxResults", limit);
        page.addProperty("total", all.size());
        page.addProperty("isLast", (long) offset + rows.size() >= all.size());
        return page;
    }

    static int next(JsonObject page, int count) throws IOException {
        var offset = integer(page, "startAt");
        long following = (long) offset + count;
        if (following > Integer.MAX_VALUE) {
            throw wrong("Jira pagination offset exceeds the supported range.");
        }
        if (page.has("isLast")) {
            var last = page.get("isLast");
            if (!last.isJsonPrimitive()
                    || !last.getAsJsonPrimitive().isBoolean()) {
                throw wrong("Invalid Jira pagination metadata.");
            }
            if (last.getAsBoolean()) {
                return -1;
            }
        } else if (page.has("total")) {
            if (following >= integer(page, "total")) {
                return -1;
            }
        } else {
            throw wrong("Jira response is missing pagination metadata.");
        }
        if (count == 0) {
            throw wrong("Jira returned an empty non-final page.");
        }
        return (int) following;
    }

    static int integer(JsonObject object, String field) throws IOException {
        try {
            var value = object.get(field);
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                throw new ArithmeticException();
            }
            var number = value.getAsBigDecimal().intValueExact();
            if (number < 0) {
                throw new ArithmeticException();
            }
            return number;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw wrong("Invalid Jira pagination metadata.");
        }
    }

    static void continuation(JiraCommand.Action action, JsonObject page,
            int count) throws IOException {
        var following = next(page, count);
        if (following >= 0) {
            action.spec.commandLine().getErr().println(
                    "Next page: repeat the same command with --start-at "
                            + following);
        }
    }

    static void render(JiraCommand.Action action, JsonArray items,
            List<String> defaults, List<String> selected,
            boolean plain, boolean noHeaders) throws IOException {
        var columns = selected == null ? defaults : selected.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
        if (columns.isEmpty()
                || columns.stream().anyMatch(value -> defaults.stream()
                        .noneMatch(allowed -> allowed.equalsIgnoreCase(value)))) {
            throw wrong("Unsupported columns; choose from "
                    + String.join(",", defaults) + ".");
        }
        var headers = columns.stream().map(value ->
                value.toUpperCase(Locale.ROOT)).toList();
        var rows = new ArrayList<List<String>>();
        for (var item : items) {
            var value = object(item);
            var row = new ArrayList<String>();
            for (var column : columns) {
                var name = defaults.stream()
                        .filter(allowed -> allowed.equalsIgnoreCase(column))
                        .findFirst().orElseThrow();
                row.add(scalar(value.get(name)));
            }
            rows.add(row);
        }
        int[] widths = new int[columns.size()];
        Arrays.setAll(widths, i -> headers.get(i).length());
        for (var row : rows) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        if (!noHeaders) {
            action.print(row(headers, widths, plain));
        }
        for (var values : rows) {
            action.print(row(values, widths, plain));
        }
    }

    private static String row(List<String> cells, int[] widths,
            boolean plain) {
        if (plain) {
            return String.join("\t", cells);
        }
        var text = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            text.append(cells.get(i));
            if (i + 1 < cells.size()) {
                text.append(" ".repeat(widths[i] - cells.get(i).length() + 2));
            }
        }
        return text.toString();
    }

    static String scalar(JsonElement value) throws IOException {
        if (value == null || value.isJsonNull()) {
            return "-";
        }
        if (!value.isJsonPrimitive()) {
            throw wrong("Unexpected Jira catalog field shape.");
        }
        return value.getAsString().replaceAll("[\\p{Cc}\\p{Cf}]", " ");
    }
}
