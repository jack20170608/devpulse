package io.github.devpulse.jira;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BulletList;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

public final class JiraMarkdown {
    public enum Format { markdown, wiki }

    private JiraMarkdown() {}

    public static JsonElement toJira(String markdown, boolean adf)
            throws IOException {
        return toJira(markdown, adf, Format.markdown);
    }

    public static JsonElement toJira(String markdown, boolean adf,
            Format format) throws IOException {
        if (format == Format.wiki) {
            if (adf) {
                throw new IOException("Jira wiki input requires REST API v2; "
                        + "use --body-format markdown for REST API v3.");
            }
            return new com.google.gson.JsonPrimitive(markdown);
        }
        var document = Parser.builder()
                .extensions(List.of(TablesExtension.create()))
                .build().parse(markdown);
        if (!adf) {
            return new com.google.gson.JsonPrimitive(wikiBlocks(
                    document));
        }
        var doc = new JsonObject();
        doc.addProperty("type", "doc");
        doc.addProperty("version", 1);
        var content = new JsonArray();
        for (var block = document.getFirstChild(); block != null;
                block = block.getNext()) {
            content.add(block(block));
        }
        doc.add("content", content);
        return doc;
    }

    public static String fromJira(JsonElement value, boolean adf)
            throws IOException {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!adf) {
            if (!value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                throw new IOException("REST API v2 description/comment "
                        + "must be a Jira wiki string.");
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
        if (node instanceof TableBlock) {
            throw new IOException("Markdown tables require REST API v2; "
                    + "ADF table uploads are not supported for REST API v3.");
        }
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
                if ((link.getTitle() != null && !link.getTitle().isEmpty())
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
                var attrs = attributes(node);
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
                        ? attributes(node).get("order")
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
                        var attrs = attributes(mark);
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

    private static String wikiBlocks(Node parent) throws IOException {
        var blocks = new ArrayList<String>();
        for (var node = parent.getFirstChild(); node != null;
                node = node.getNext()) {
            if (!(node instanceof LinkReferenceDefinition)) {
                blocks.add(wikiBlock(node));
            }
        }
        return String.join("\n\n", blocks);
    }

    private static String wikiBlock(Node node) throws IOException {
        return switch (node) {
            case Paragraph paragraph -> wikiInline(paragraph);
            case Heading heading -> "h" + heading.getLevel() + ". "
                    + wikiInline(heading);
            case BulletList list -> wikiList(list, "");
            case OrderedList list -> wikiList(list, "");
            case TableBlock table -> wikiTable(table);
            case BlockQuote quote -> {
                if (quote.getParent() instanceof BlockQuote) {
                    throw unsupportedWiki();
                }
                yield "{quote}\n" + wikiBlocks(quote) + "\n{quote}";
            }
            case FencedCodeBlock code -> wikiCode(
                    code.getLiteral(), code.getInfo());
            case IndentedCodeBlock code -> wikiCode(code.getLiteral(), "");
            case ThematicBreak ignored -> "----";
            default -> throw unsupportedWiki();
        };
    }

    private static String wikiTable(TableBlock table) throws IOException {
        var rows = new ArrayList<String>();
        for (var section = table.getFirstChild(); section != null;
                section = section.getNext()) {
            var separator = section instanceof TableHead ? "||" : "|";
            for (var row = section.getFirstChild(); row != null;
                    row = row.getNext()) {
                var cells = new ArrayList<String>();
                for (var cell = row.getFirstChild(); cell != null;
                        cell = cell.getNext()) {
                    if (!(cell instanceof TableCell)) {
                        throw unsupportedWiki();
                    }
                    var text = wikiInline(cell, true);
                    if (text.contains("\n") || text.contains("\r")) {
                        throw unsupportedWiki();
                    }
                    cells.add(text);
                }
                rows.add(separator + String.join(separator, cells) + separator);
            }
        }
        return String.join("\n", rows);
    }

    private static String wikiList(Node list, String prefix)
            throws IOException {
        var marker = prefix + (list instanceof OrderedList ? "#" : "*");
        var lines = new ArrayList<String>();
        for (var item = list.getFirstChild(); item != null;
                item = item.getNext()) {
            if (!(item instanceof ListItem)
                    || !(item.getFirstChild() instanceof Paragraph)) {
                throw unsupportedWiki();
            }
            lines.add(marker + " " + wikiInline(item.getFirstChild()));
            for (var child = item.getFirstChild().getNext(); child != null;
                    child = child.getNext()) {
                if (!(child instanceof BulletList)
                        && !(child instanceof OrderedList)) {
                    throw unsupportedWiki();
                }
                lines.add(wikiList(child, marker));
            }
        }
        return String.join("\n", lines);
    }

    private static String wikiCode(String literal, String info)
            throws IOException {
        if ((!info.isBlank() && !info.matches("[A-Za-z0-9_+#.-]+"))
                || literal.matches("(?is).*\\{code(?::[^}]*)?\\}.*")) {
            throw unsupportedWiki();
        }
        return "{code" + (info.isBlank() ? "" : ":" + info) + "}\n"
                + literal.replaceFirst("\\n$", "") + "\n{code}";
    }

    private static String wikiInline(Node parent) throws IOException {
        return wikiInline(parent, false);
    }

    private static String wikiInline(Node parent, boolean tableCell)
            throws IOException {
        var result = new StringBuilder();
        for (var node = parent.getFirstChild(); node != null;
                node = node.getNext()) {
            result.append(switch (node) {
                case Text text -> wikiEscaped(text.getLiteral());
                case StrongEmphasis strong -> "*" + wikiInline(strong, tableCell) + "*";
                case Emphasis emphasis -> "_" + wikiInline(emphasis, tableCell) + "_";
                case Code code -> {
                    if (code.getLiteral().contains("}}")) {
                        throw unsupportedWiki();
                    }
                    yield "{{" + (tableCell
                            ? code.getLiteral().replace("|", "\\|")
                            : code.getLiteral()) + "}}";
                }
                case Link link -> {
                    requireWikiDestination(link.getDestination());
                    if (link.getTitle() != null && !link.getTitle().isEmpty()) {
                        throw unsupportedWiki();
                    }
                    yield "[" + wikiInline(link, tableCell) + "|"
                            + link.getDestination() + "]";
                }
                case Image image -> {
                    requireWikiDestination(image.getDestination());
                    if ((image.getTitle() != null && !image.getTitle().isEmpty())
                            || image.getDestination().contains("!")) {
                        throw unsupportedWiki();
                    }
                    var alt = new StringBuilder();
                    for (var child = image.getFirstChild(); child != null;
                            child = child.getNext()) {
                        if (!(child instanceof Text text)
                                || text.getLiteral().matches("(?s).*[|!,=\\r\\n].*")) {
                            throw unsupportedWiki();
                        }
                        alt.append(text.getLiteral());
                    }
                    yield "!" + image.getDestination()
                            + (alt.isEmpty() ? "" : "|alt=" + alt) + "!";
                }
                case SoftLineBreak ignored -> "\n";
                case HardLineBreak ignored -> "\\\\\n";
                default -> throw unsupportedWiki();
            });
        }
        return result.toString();
    }

    private static void requireWikiDestination(String destination)
            throws IOException {
        if (destination.isBlank()
                || destination.matches("(?s).*[\\[\\]|\\s].*")) {
            throw unsupportedWiki();
        }
    }

    private static String wikiEscaped(String text) {
        var output = new StringBuilder();
        for (var character : text.toCharArray()) {
            if (character == '\\') {
                // Wiki reserves two backslashes for a hard break.
                output.append("{{\\}}");
            } else {
                if ("[]{}*_!|#^~+?-".indexOf(character) >= 0) {
                    output.append('\\');
                }
                output.append(character);
            }
        }
        return output.toString().replaceAll("(?m)^(h[1-6]|bq)\\.", "$1\\\\.");
    }

    private static IOException unsupportedWiki() {
        return new IOException("Markdown contains content that cannot be "
                + "represented safely as Jira wiki; simplify it or use "
                + "--body-format wiki with native Jira wiki input.");
    }

    private static JsonObject attributes(JsonObject node) throws IOException {
        var value = node.get("attrs");
        if (value == null || !value.isJsonObject()) {
            throw unsupported();
        }
        return value.getAsJsonObject();
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
