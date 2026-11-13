package io.github.devpulse.jira;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only Markdown presentation, separate from lossless export conversion. */
public final class JiraDescriptionMarkdown {
    private static final Pattern CODE = Pattern.compile(
            "(?<![\\\\{])\\{(code|noformat)(?::([^}]*))?\\}(?!\\})"
                    + "(.*?)(?<![\\\\{])\\{\\1\\}(?!\\})",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^h([1-6])\\.\\s+(.*)$");
    private static final Pattern LIST = Pattern.compile("^([*#]+)\\s+(.*)$");
    private record WikiLines(String text, boolean quote) {}

    private JiraDescriptionMarkdown() {}

    public static String render(JsonElement value, boolean adf) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!adf && value.isJsonPrimitive()) {
            return wiki(value.getAsString().replace("\r\n", "\n")
                    .replace('\r', '\n')).strip();
        }
        return node(value, 0).strip();
    }

    private static String wiki(String source) {
        var result = new StringBuilder();
        var blocks = CODE.matcher(source);
        var start = 0;
        var quote = false;
        while (blocks.find()) {
            var preceding = wikiLines(source.substring(start, blocks.start()), quote);
            result.append(preceding.text());
            quote = preceding.quote();
            var info = blocks.group(2) == null ? "" : blocks.group(2);
            var language = info.split("\\|", 2)[0].replaceFirst("^language=", "");
            var code = fenced(blocks.group(3).replaceFirst("^\\n", "")
                    .replaceFirst("\\n$", ""), language);
            result.append(quote ? "\n>\n" : "\n\n")
                    .append(quote ? quoted(code) : code)
                    .append(quote ? "\n>\n" : "\n\n");
            start = blocks.end();
        }
        return result.append(wikiLines(source.substring(start), quote).text()).toString();
    }

    private static WikiLines wikiLines(String source, boolean quote) {
        var lines = source.split("\n", -1);
        var output = new ArrayList<String>();
        var numbers = new HashMap<String, Integer>();
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            var stripped = line.strip();
            if (stripped.equalsIgnoreCase("{quote}")
                    || stripped.matches("(?i)\\{panel(?::[^}]*)?\\}")) {
                quote = !quote;
                if (quote) {
                    var title = Pattern.compile("(?i)(?:[:|])title=([^|}]+)")
                            .matcher(stripped);
                    if (title.find()) {
                        output.add("> **" + escaped(title.group(1)) + "**");
                    }
                }
                output.add(quote ? ">" : "");
                continue;
            }
            if (wikiTableLine(stripped)) {
                var rows = new ArrayList<List<String>>();
                while (i < lines.length && wikiTableLine(lines[i].strip())) {
                    rows.add(wikiCells(lines[i].strip()));
                    i++;
                }
                i--;
                var table = table(rows);
                output.add(quote ? quoted(table) : table);
                numbers.clear();
                continue;
            }
            var heading = HEADING.matcher(stripped);
            var list = LIST.matcher(stripped);
            String rendered;
            if (heading.matches()) {
                rendered = "#".repeat(Integer.parseInt(heading.group(1))) + " "
                        + wikiInline(heading.group(2), 0);
            } else if (list.matches()) {
                var markers = list.group(1);
                numbers.keySet().removeIf(key -> key.length() > markers.length());
                var ordered = markers.endsWith("#");
                var marker = ordered ? numbers.merge(markers, 1, Integer::sum) + ". " : "- ";
                var indent = 0;
                for (var j = 0; j < markers.length() - 1; j++) {
                    indent += markers.charAt(j) == '#' ? 3 : 2;
                }
                rendered = " ".repeat(indent) + marker + wikiInline(list.group(2), 0);
            } else {
                numbers.clear();
                rendered = stripped.equals("----") ? "---"
                        : stripped.startsWith("bq. ") ? "> " + wikiInline(stripped.substring(4), 0)
                        : escapeBlockSyntax(wikiInline(line, 0));
            }
            if (rendered.endsWith("  \n") && i + 1 < lines.length) {
                rendered = rendered.substring(0, rendered.length() - 1);
            }
            output.add(quote ? quoted(rendered) : rendered);
        }
        return new WikiLines(String.join("\n", output), quote);
    }

    private static boolean wikiTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|")
                && line.length() >= (line.startsWith("||") ? 4 : 2);
    }

    private static List<String> wikiCells(String line) {
        var header = line.startsWith("||");
        var width = header ? 2 : 1;
        var separator = header ? "||" : "|";
        var content = line.substring(width, line.length() - width);
        var cells = new ArrayList<String>();
        var start = 0;
        var brackets = 0;
        var braces = 0;
        var image = false;
        for (var i = 0; i < content.length(); i++) {
            if (content.startsWith("{{", i)) {
                var end = content.indexOf("}}", i + 2);
                if (end >= 0) {
                    i = end + 1;
                }
            } else if (content.charAt(i) == '\\') {
                i++;
            } else if (content.charAt(i) == '[') {
                brackets++;
            } else if (content.charAt(i) == ']') {
                brackets--;
            } else if (content.charAt(i) == '{') {
                braces++;
            } else if (content.charAt(i) == '}') {
                braces--;
            } else if (content.charAt(i) == '!') {
                image = !image;
            } else if (brackets == 0 && braces == 0 && !image
                    && content.startsWith(separator, i)) {
                cells.add(wikiInline(content.substring(start, i).strip(), 0, true));
                i += width - 1;
                start = i + 1;
            }
        }
        cells.add(wikiInline(content.substring(start).strip(), 0, true));
        return cells;
    }

    private static String wikiInline(String text, int depth) {
        return wikiInline(text, depth, false);
    }

    private static String wikiInline(String text, int depth, boolean tableCell) {
        if (depth > 30) {
            return escaped(text);
        }
        var output = new StringBuilder();
        for (var i = 0; i < text.length();) {
            if (text.startsWith("{{", i)) {
                var end = text.indexOf("}}", i + 2);
                if (end >= 0) {
                    var literal = text.substring(i + 2, end);
                    output.append(codeSpan(tableCell ? literal.replace("\\|", "|") : literal));
                    i = end + 2;
                    continue;
                }
            }
            var character = text.charAt(i);
            if (character == '\\' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '\\') {
                    output.append("  \n");
                } else {
                    output.append(escaped(text.substring(i + 1, i + 2)));
                }
                i += 2;
                continue;
            }
            if (character == '[' || character == '!') {
                var end = wikiDelimiter(text, i + 1, character == '[' ? ']' : '!');
                if (end > i + 1) {
                    var value = text.substring(i + 1, end);
                    var separator = wikiDelimiter(value, 0, '|');
                    if (character == '!') {
                        var url = separator < 0 ? value : value.substring(0, separator);
                        var alt = Pattern.compile("(?:^|\\|)alt=([^|]*)").matcher(value);
                        output.append("![").append(alt.find() ? escaped(alt.group(1)) : "")
                                .append("](").append(destination(url)).append(")");
                    } else if (separator >= 0) {
                        output.append("[").append(wikiInline(
                                value.substring(0, separator), depth + 1, tableCell))
                                .append("](").append(destination(
                                        unescapeWiki(value.substring(separator + 1)))).append(")");
                    } else if (value.startsWith("~")) {
                        output.append(escaped("@" + value.substring(1)));
                    } else if (value.matches("(?i)(?:https?://|mailto:).*")) {
                        output.append("[").append(escaped(value)).append("](")
                                .append(destination(value)).append(")");
                    } else {
                        output.append(escaped("[" + value + "]"));
                    }
                    i = end + 1;
                    continue;
                }
            }
            if ("*_+-~^".indexOf(character) >= 0 && i + 1 < text.length()
                    && !Character.isWhitespace(text.charAt(i + 1))
                    && (i == 0 || !wordCharacter(text.charAt(i - 1)))) {
                var end = formatEnd(text, i + 1, character);
                if (end > i + 1) {
                    var inner = wikiInline(text.substring(i + 1, end), depth + 1, tableCell);
                    output.append(switch (character) {
                        case '*' -> "**" + inner + "**";
                        case '_' -> "*" + inner + "*";
                        case '-' -> "~~" + inner + "~~";
                        case '+' -> "<u>" + inner + "</u>";
                        case '~' -> "<sub>" + inner + "</sub>";
                        default -> "<sup>" + inner + "</sup>";
                    });
                    i = end + 1;
                    continue;
                }
            }
            output.append(escaped(text.substring(i, i + 1)));
            i++;
        }
        return output.toString();
    }

    private static int wikiDelimiter(String text, int start, char delimiter) {
        for (var i = start; i < text.length(); i++) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                i++;
            } else if (text.startsWith("{{", i)) {
                var end = text.indexOf("}}", i + 2);
                if (end >= 0) {
                    i = end + 1;
                }
            } else if (text.charAt(i) == delimiter) {
                return i;
            }
        }
        return -1;
    }

    private static int formatEnd(String text, int start, char delimiter) {
        for (var i = start; i < text.length(); i++) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                i++;
            } else if (text.startsWith("{{", i)) {
                var end = text.indexOf("}}", i + 2);
                if (end >= 0) {
                    i = end + 1;
                }
            } else if (text.charAt(i) == '[' || text.charAt(i) == '!') {
                var end = wikiDelimiter(text, i + 1, text.charAt(i) == '[' ? ']' : '!');
                if (end >= 0) {
                    i = end;
                }
            } else if (text.charAt(i) == delimiter
                    && !Character.isWhitespace(text.charAt(i - 1))
                    && (i + 1 == text.length() || !wordCharacter(text.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean wordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static String unescapeWiki(String text) {
        var output = new StringBuilder();
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                i++;
            }
            output.append(text.charAt(i));
        }
        return output.toString();
    }

    private static String escapeBlockSyntax(String text) {
        return text.replaceAll("(?m)^( {0,3})([-+])(?=\\s)", "$1\\\\$2")
                .replaceAll("(?m)^( {0,3}\\d{1,9})([.)])(?=\\s)", "$1\\\\$2")
                .replaceAll("(?m)^( {0,3})([-=])(?=[-= \\t]*$)", "$1\\\\$2");
    }

    private static String node(JsonElement value, int depth) {
        if (value == null || value.isJsonNull() || depth > 100) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return escaped(value.getAsString());
        }
        if (value.isJsonArray()) {
            var output = new StringBuilder();
            for (var item : value.getAsJsonArray()) {
                output.append(node(item, depth + 1));
            }
            return output.toString();
        }
        var object = value.getAsJsonObject();
        var type = string(object, "type");
        var attrs = object.has("attrs") && object.get("attrs").isJsonObject()
                ? object.getAsJsonObject("attrs") : new JsonObject();
        return switch (type) {
            case "doc", "blockquote", "expand", "nestedExpand" -> {
                var blocks = blocks(object, depth);
                yield type.equals("blockquote") ? quoted(blocks) : blocks;
            }
            case "paragraph" -> escapeBlockSyntax(children(object, depth));
            case "listItem" -> children(object, depth);
            case "tableCell", "tableHeader" -> blocks(object, depth);
            case "heading" -> "#".repeat(number(attrs, "level", 1, 6)) + " "
                    + children(object, depth);
            case "hardBreak" -> "  \n";
            case "rule" -> "---";
            case "codeBlock" -> fenced(rawText(object), string(attrs, "language"));
            case "bulletList", "orderedList", "taskList" -> list(object, depth);
            case "table" -> {
                var rows = new ArrayList<List<String>>();
                if (object.has("content") && object.get("content").isJsonArray()) {
                    for (var row : object.getAsJsonArray("content")) {
                        var cells = new ArrayList<String>();
                        if (row.isJsonObject() && row.getAsJsonObject().has("content")
                                && row.getAsJsonObject().get("content").isJsonArray()) {
                            for (var cell : row.getAsJsonObject().getAsJsonArray("content")) {
                                cells.add(node(cell, depth + 1));
                            }
                        }
                        rows.add(cells);
                    }
                }
                yield table(rows);
            }
            case "text" -> marked(object);
            case "mention" -> escaped(string(attrs, "text").isEmpty()
                    ? "@" + string(attrs, "id") : string(attrs, "text"));
            case "emoji" -> escaped(string(attrs, "text").isEmpty()
                    ? string(attrs, "shortName") : string(attrs, "text"));
            case "inlineCard", "blockCard" -> "[" + escaped(string(attrs, "url"))
                    + "](" + destination(string(attrs, "url")) + ")";
            default -> {
                var content = children(object, depth);
                if (!content.isEmpty()) {
                    yield content;
                }
                var label = string(attrs, "text");
                if (label.isEmpty()) {
                    label = string(attrs, "alt");
                }
                yield label.isEmpty() ? escaped("[" + type + "]") : escaped(label);
            }
        };
    }

    private static String marked(JsonObject text) {
        var output = escaped(string(text, "text"));
        if (text.has("marks") && text.get("marks").isJsonArray()) {
            for (var value : text.getAsJsonArray("marks")) {
                if (value.isJsonObject() && string(value.getAsJsonObject(), "type").equals("code")) {
                    output = codeSpan(string(text, "text"));
                }
            }
            for (var value : text.getAsJsonArray("marks")) {
                if (!value.isJsonObject()) {
                    continue;
                }
                var mark = value.getAsJsonObject();
                output = switch (string(mark, "type")) {
                    case "strong" -> "**" + output + "**";
                    case "em" -> "*" + output + "*";
                    case "strike" -> "~~" + output + "~~";
                    case "underline" -> "<u>" + output + "</u>";
                    case "link" -> mark.has("attrs") && mark.get("attrs").isJsonObject()
                            ? "[" + output + "](" + destination(string(
                                    mark.getAsJsonObject("attrs"), "href")) + ")" : output;
                    default -> output;
                };
            }
        }
        return output;
    }

    private static String children(JsonObject object, int depth) {
        return object.has("content") ? node(object.get("content"), depth + 1) : "";
    }

    private static String blocks(JsonObject object, int depth) {
        var output = new ArrayList<String>();
        if (object.has("content") && object.get("content").isJsonArray()) {
            for (var value : object.getAsJsonArray("content")) {
                output.add(node(value, depth + 1));
            }
        }
        return String.join("\n\n", output);
    }

    private static String list(JsonObject object, int depth) {
        var output = new ArrayList<String>();
        var ordered = string(object, "type").equals("orderedList");
        var index = object.has("attrs") && object.get("attrs").isJsonObject()
                ? number(object.getAsJsonObject("attrs"), "order", 1, Integer.MAX_VALUE) : 1;
        if (object.has("content") && object.get("content").isJsonArray()) {
            for (var value : object.getAsJsonArray("content")) {
                var item = value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
                var marker = ordered ? index++ + ". " : "- ";
                if (string(item, "type").equals("taskItem")) {
                    var attrs = item.has("attrs") && item.get("attrs").isJsonObject()
                            ? item.getAsJsonObject("attrs") : new JsonObject();
                    marker += string(attrs, "state").matches("DONE|CHECKED") ? "[x] " : "[ ] ";
                }
                var content = string(item, "type").equals("taskItem")
                        ? children(item, depth) : blocks(item, depth);
                output.add(marker + content.replace("\n",
                        "\n" + " ".repeat(ordered ? marker.length() : 2)));
            }
        }
        return String.join("\n", output);
    }

    private static String table(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        var width = rows.stream().mapToInt(List::size).max().orElse(0);
        var output = new ArrayList<String>();
        for (var row : rows) {
            var cells = new ArrayList<String>();
            for (var i = 0; i < width; i++) {
                cells.add(i < row.size() ? row.get(i).replace("|", "\\|")
                        .replace("\n", "<br>") : "");
            }
            output.add("| " + String.join(" | ", cells) + " |");
            if (output.size() == 1) {
                output.add("| " + String.join(" | ", java.util.Collections.nCopies(width, "---")) + " |");
            }
        }
        return String.join("\n", output);
    }

    private static String rawText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonArray()) {
            var result = new StringBuilder();
            for (var item : value.getAsJsonArray()) {
                result.append(rawText(item));
            }
            return result.toString();
        }
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            return string(object, "text") + (object.has("content")
                    ? rawText(object.get("content")) : "");
        }
        return "";
    }

    private static String string(JsonObject object, String field) {
        var value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int number(JsonObject object, String field, int fallback, int max) {
        try {
            return Math.clamp(Integer.parseInt(string(object, field)), fallback, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String escaped(String text) {
        return text.replace("\\", "\\\\").replaceAll("([`*_\\[\\]<>#!~])", "\\\\$1");
    }

    private static String destination(String text) {
        return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                .replace(" ", "%20").replace("\n", "%0A").replace("\r", "%0D")
                .replace("<", "%3C").replace(">", "%3E");
    }

    private static String codeSpan(String text) {
        var fence = "`".repeat(longestRun(text, '`') + 1);
        boolean padding = text.startsWith("`") || text.endsWith("`")
                || (text.startsWith(" ") && text.endsWith(" ") && !text.isBlank());
        return fence + (padding ? " " : "") + text + (padding ? " " : "") + fence;
    }

    private static String fenced(String text, String language) {
        var fence = "`".repeat(Math.max(3, longestRun(text, '`') + 1));
        return fence + (language.matches("[A-Za-z0-9_+#.-]*") ? language : "")
                + "\n" + text + (text.endsWith("\n") ? "" : "\n") + fence;
    }

    private static int longestRun(String text, char delimiter) {
        var longest = 0;
        var run = 0;
        for (var character : text.toCharArray()) {
            run = character == delimiter ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static String quoted(String text) {
        return "> " + text.replace("\n", "\n> ");
    }
}
