package io.github.devpulse.jira;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

public final class JiraMarkdown {
    private JiraMarkdown() {}

    public static JsonElement toJira(String markdown, boolean cloud)
            throws IOException {
        if (!cloud) {
            // Jira Data Center uses its own wiki renderer, not ADF.
            if (!plain(markdown)) {
                throw new IOException("Data Center supports plain-text "
                        + "descriptions/comments only; rich Markdown "
                        + "cannot be converted without losing formatting.");
            }
            return new com.google.gson.JsonPrimitive(markdown);
        }
        var doc = new JsonObject();
        doc.addProperty("type", "doc");
        doc.addProperty("version", 1);
        var content = new JsonArray();
        var document = Parser.builder().build().parse(markdown);
        for (var block = document.getFirstChild(); block != null;
                block = block.getNext()) {
            content.add(block(block));
        }
        doc.add("content", content);
        return doc;
    }

    public static String fromJira(JsonElement value, boolean cloud)
            throws IOException {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!cloud) {
            if (!value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || !plain(value.getAsString())) {
                throw new IOException("Data Center description contains "
                        + "wiki markup or unsupported content; refusing "
                        + "lossy Markdown export.");
            }
            return value.getAsString();
        }
        var doc = object(value, "doc");
        if (doc.get("version") == null
                || doc.get("version").getAsInt() != 1
                || !only(doc, "type", "version", "content")) {
            throw unsupported();
        }
        var blocks = new ArrayList<String>();
        for (var node : array(doc, "content")) {
            blocks.add(renderBlock(node));
        }
        var markdown = String.join("\n\n", blocks)
                + (blocks.isEmpty() ? "" : "\n");
        if (!toJira(markdown, true).equals(value)) {
            throw unsupported();
        }
        return markdown;
    }

    private static JsonObject block(Node node) throws IOException {
        if (node instanceof Paragraph paragraph) {
            return container("paragraph", children(paragraph));
        }
        if (node instanceof Heading heading && heading.getLevel() <= 6) {
            var result = container("heading", children(heading));
            var attrs = new JsonObject();
            attrs.addProperty("level", heading.getLevel());
            result.add("attrs", attrs);
            return result;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            var list = container(node instanceof BulletList
                    ? "bulletList" : "orderedList", new JsonArray());
            if (node instanceof OrderedList ordered) {
                var attrs = new JsonObject();
                attrs.addProperty("order", ordered.getStartNumber());
                list.add("attrs", attrs);
            }
            for (var item = node.getFirstChild(); item != null;
                    item = item.getNext()) {
                if (!(item instanceof ListItem)
                        || !(item.getFirstChild() instanceof Paragraph)
                        || item.getFirstChild().getNext() != null) {
                    throw unsupported();
                }
                var content = new JsonArray();
                content.add(block(item.getFirstChild()));
                list.getAsJsonArray("content").add(
                        container("listItem", content));
            }
            return list;
        }
        if (node instanceof FencedCodeBlock fenced) {
            if (!fenced.getInfo().isBlank()) {
                throw new IOException("Language-tagged code fences are "
                        + "not supported for safe Jira round-trips.");
            }
            var text = fenced.getLiteral().replaceFirst("\\n$", "");
            var content = new JsonArray();
            content.add(text(text));
            return container("codeBlock", content);
        }
        throw unsupported();
    }

    private static JsonArray children(Node parent) throws IOException {
        var result = new JsonArray();
        for (var child = parent.getFirstChild(); child != null;
                child = child.getNext()) {
            if (child instanceof Text text) {
                result.add(text(text.getLiteral()));
            } else if (child instanceof Code code) {
                result.add(marked(code.getLiteral(), "code", null));
            } else if (child instanceof StrongEmphasis) {
                markedChildren(child, "strong", null, result);
            } else if (child instanceof Emphasis) {
                markedChildren(child, "em", null, result);
            } else if (child instanceof Link link) {
                if (!link.getTitle().isEmpty()
                        || !link.getDestination().matches("https?://.+")) {
                    throw unsupported();
                }
                var attrs = new JsonObject();
                attrs.addProperty("href", link.getDestination());
                markedChildren(link, "link", attrs, result);
            } else if (child instanceof HardLineBreak) {
                result.add(container("hardBreak", null));
            } else {
                throw unsupported();
            }
        }
        return result;
    }

    private static void markedChildren(Node parent, String mark,
            JsonObject attrs, JsonArray result) throws IOException {
        for (var child = parent.getFirstChild(); child != null;
                child = child.getNext()) {
            if (!(child instanceof Text text)) {
                throw unsupported();
            }
            result.add(marked(text.getLiteral(), mark, attrs));
        }
    }

    private static JsonObject marked(String value, String type,
            JsonObject attrs) {
        var node = text(value);
        var mark = new JsonObject();
        mark.addProperty("type", type);
        if (attrs != null) {
            mark.add("attrs", attrs);
        }
        var marks = new JsonArray();
        marks.add(mark);
        node.add("marks", marks);
        return node;
    }

    private static JsonObject text(String value) {
        var result = new JsonObject();
        result.addProperty("type", "text");
        result.addProperty("text", value);
        return result;
    }

    private static JsonObject container(String type, JsonArray content) {
        var result = new JsonObject();
        result.addProperty("type", type);
        if (content != null) {
            result.add("content", content);
        }
        return result;
    }

    private static String renderBlock(JsonElement element)
            throws IOException {
        var node = object(element, null);
        var type = node.get("type").getAsString();
        return switch (type) {
            case "paragraph" -> {
                require(node, "type", "content");
                yield renderInline(array(node, "content"));
            }
            case "heading" -> {
                require(node, "type", "attrs", "content");
                var attrs = object(node.get("attrs"), null);
                require(attrs, "level");
                var level = attrs.get("level").getAsInt();
                if (level < 1 || level > 6) {
                    throw unsupported();
                }
                yield "#".repeat(level) + " "
                        + renderInline(array(node, "content"));
            }
            case "bulletList", "orderedList" -> {
                require(node, type.equals("bulletList")
                        ? new String[]{"type", "content"}
                        : new String[]{"type", "attrs", "content"});
                var lines = new ArrayList<String>();
                var index = type.equals("orderedList") && node.has("attrs")
                        ? object(node.get("attrs"), null).get("order")
                                .getAsInt() : 1;
                for (var item : array(node, "content")) {
                    var listItem = object(item, "listItem");
                    require(listItem, "type", "content");
                    var blocks = array(listItem, "content");
                    if (blocks.size() != 1) {
                        throw unsupported();
                    }
                    var paragraph = object(blocks.get(0), "paragraph");
                    lines.add((type.equals("bulletList")
                            ? "- " : index++ + ". ")
                            + renderInline(array(paragraph, "content")));
                }
                yield String.join("\n", lines);
            }
            case "codeBlock" -> {
                require(node, "type", "content");
                var text = renderCode(array(node, "content"));
                if (text.contains("```")) {
                    throw unsupported();
                }
                yield "```\n" + text + "\n```";
            }
            default -> throw unsupported();
        };
    }

    private static String renderCode(JsonArray nodes) throws IOException {
        if (nodes.size() != 1) {
            throw unsupported();
        }
        var text = object(nodes.get(0), "text");
        require(text, "type", "text");
        return text.get("text").getAsString();
    }

    private static String renderInline(JsonArray nodes)
            throws IOException {
        var result = new StringBuilder();
        for (var element : nodes) {
            var node = object(element, null);
            var type = node.get("type").getAsString();
            if (type.equals("hardBreak")) {
                require(node, "type");
                result.append("  \n");
                continue;
            }
            if (!type.equals("text")) {
                throw unsupported();
            }
            var value = node.get("text").getAsString();
            if (value.contains("\n") || value.contains("\r")) {
                throw unsupported();
            }
            if (node.has("marks")) {
                require(node, "type", "text", "marks");
                var marks = array(node, "marks");
                if (marks.size() != 1) {
                    throw unsupported();
                }
                var mark = object(marks.get(0), null);
                require(mark, mark.get("type").getAsString().equals("link")
                        ? new String[]{"type", "attrs"}
                        : new String[]{"type"});
                value = switch (mark.get("type").getAsString()) {
                    case "strong" -> "**" + escaped(value) + "**";
                    case "em" -> "*" + escaped(value) + "*";
                    case "code" -> {
                        if (value.contains("`")) {
                            throw unsupported();
                        }
                        yield "`" + value + "`";
                    }
                    case "link" -> {
                        var attrs = object(mark.get("attrs"), null);
                        require(attrs, "href");
                        var href = attrs.get("href").getAsString();
                        if (!href.matches("https?://[^\\s)]+")) {
                            throw unsupported();
                        }
                        yield "[" + escaped(value) + "](" + href + ")";
                    }
                    default -> throw unsupported();
                };
            } else {
                require(node, "type", "text");
                value = escaped(value);
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String escaped(String text) {
        return text.replace("\\", "\\\\")
                .replace("*", "\\*").replace("_", "\\_")
                .replace("`", "\\`").replace("[", "\\[")
                .replace("]", "\\]").replace("<", "\\<")
                .replace("#", "\\#").replace("!", "\\!")
                .replace("-", "\\-").replace("+", "\\+")
                .replace(".", "\\.").replace(">", "\\>")
                .replace("|", "\\|");
    }

    private static void require(JsonObject node, String... names)
            throws IOException {
        if (!only(node, names)) {
            throw unsupported();
        }
    }

    private static boolean only(JsonObject node, String... names) {
        return node.keySet().stream().allMatch(
                name -> java.util.List.of(names).contains(name));
    }

    private static boolean plain(String text) {
        return !text.matches("(?s).*(?:^|\\n)\\s*(?:[#*>-] |"
                + "[0-9]+\\. |```|\\{code\\}|h[1-6]\\. |"
                + "\\|\\||\\{panel).*")
                && !text.matches("(?s).*\\[.+\\]\\(.+\\).*")
                && !text.matches("(?s).*\\[[^\\]]+\\].*")
                && !text.matches("(?s).*\\*\\S[^*]*\\*.*")
                && !text.matches("(?s).*_\\S[^_]*_.*")
                && !text.matches("(?s).*!\\S[^!]*!.*")
                && !text.contains("**") && !text.contains("{");
    }

    private static JsonObject object(JsonElement value, String type)
            throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw unsupported();
        }
        var node = value.getAsJsonObject();
        if (!node.has("type") || (type != null
                && !node.get("type").getAsString().equals(type))) {
            throw unsupported();
        }
        return node;
    }

    private static JsonArray array(JsonObject node, String key)
            throws IOException {
        if (!node.has(key) || !node.get(key).isJsonArray()) {
            throw unsupported();
        }
        return node.getAsJsonArray(key);
    }

    private static IOException unsupported() {
        return new IOException("Unsupported Jira rich-text node; "
                + "refusing lossy Markdown conversion.");
    }
}
