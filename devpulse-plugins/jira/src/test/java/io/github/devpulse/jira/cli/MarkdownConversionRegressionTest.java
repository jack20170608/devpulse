package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.devpulse.jira.JiraDescriptionMarkdown;
import io.github.devpulse.jira.JiraMarkdown;
import java.util.List;
import java.util.stream.Stream;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MarkdownConversionRegressionTest {
    private static final Parser MARKDOWN = Parser.builder()
            .extensions(List.of(TablesExtension.create())).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().build();

    @ParameterizedTest(name = "{0}")
    @MethodSource("wikiCases")
    void rendersWikiWithoutCorruptingLiteralText(String name, String wiki, String expected) {
        assertThat(JiraDescriptionMarkdown.render(new JsonPrimitive(wiki), false))
                .isEqualTo(expected.strip());
    }

    static Stream<Arguments> wikiCases() {
        return Stream.of(
                Arguments.of("service name", "sb-test-1", "sb-test-1"),
                Arguments.of("long service name", "Need to restart the sb-pi-good-main-service",
                        "Need to restart the sb-pi-good-main-service"),
                Arguments.of("identifier", "config_test_prod", "config\\_test\\_prod"),
                Arguments.of("expression", "x+y+z", "x+y+z"),
                Arguments.of("literal tilde", "a~b~c", "a\\~b\\~c"),
                Arguments.of("literal caret", "a^b^c", "a^b^c"),
                Arguments.of("strikethrough", "-removed-", "~~removed~~"),
                Arguments.of("emphasis", "*bold* and _italic_", "**bold** and *italic*"),
                Arguments.of("nested emphasis", "*bold _italic_*", "**bold *italic***"),
                Arguments.of("escaped closing delimiter", "*a\\*b*", "**a\\*b**"),
                Arguments.of("escaped opening delimiter", "\\-removed\\-", "-removed-"),
                Arguments.of("pipe in link", "[a\\|b|https://example.com]", "[a|b](https://example.com)"),
                Arguments.of("bracket in link", "[a\\]b|https://example.com]", "[a\\]b](https://example.com)"),
                Arguments.of("code in link", "[{{a|b}}|https://example.com]", "[`a|b`](https://example.com)"),
                Arguments.of("literal heading", "\\# Not a heading", "\\# Not a heading"),
                Arguments.of("literal list", "\\- Not a list", "\\- Not a list"),
                Arguments.of("literal number", "1. ordinary text", "1\\. ordinary text"),
                Arguments.of("literal setext", "text\n--", "text\n\\--"),
                Arguments.of("code spaces", "{{ a }}", "`  a  `"),
                Arguments.of("code consisting of spaces", "{{   }}", "`   `"),
                Arguments.of("code backticks", "{{`a`}}", "`` `a` ``"),
                Arguments.of("hard break", "first\\\\second", "first  \nsecond"),
                Arguments.of("heading", "h2. Heading", "## Heading"),
                Arguments.of("list", "* First\n* Second", "- First\n- Second"),
                Arguments.of("quote", "{quote}\nquoted\n{quote}", ">\n> quoted\n"),
                Arguments.of("fenced code", "{code:java}\na-b-c\n{code}", "```java\na-b-c\n```"),
                Arguments.of("service in table", "||Name||Value||\n|service|sb-test-1|",
                        "| Name | Value |\n| --- | --- |\n| service | sb-test-1 |"),
                Arguments.of("pipe in table", "||Name||\n|a\\|b|", "| Name |\n| --- |\n| a\\|b |"),
                Arguments.of("code pipe in table", "||Name||\n|{{a\\|b}}|", "| Name |\n| --- |\n| `a\\|b` |"),
                Arguments.of("backslash in table", "||Path||Value||\n|C:{{\\}}tools|ok|",
                        "| Path | Value |\n| --- | --- |\n| C:`\\`tools | ok |"));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("roundTripCases")
    void preservesMarkdownMeaningThroughWiki(String source) throws Exception {
        var wiki = JiraMarkdown.toJira(source, false);
        var rendered = JiraDescriptionMarkdown.render(wiki, false);

        assertThat(html(rendered)).isEqualTo(html(source));
    }

    static Stream<String> roundTripCases() {
        return Stream.of(
                "sb-test-1", "**a*b**", "[a|b](https://example.com)",
                "[a\\]b](https://example.com)", "[`a|b`](https://example.com)",
                "\\# Not a heading", "\\- Not a list", "1\\. ordinary text",
                "`  a  `", "`   `", "**bold** and *italic*", "- First\n- Second",
                "## Heading", "first  \nsecond", "`` `a` ``",
                "| Name | Value |\n| --- | --- |\n| service | sb-test-1 |",
                "| Name |\n| --- |\n| a\\|b |",
                "| Name |\n| --- |\n| `a\\|b` |",
                "**[a|b](https://example.com/a*b*c)**",
                "**`a*b`**", "literal \\! punctuation",
                "**![alt](https://example.com/a*b*c.png)**");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adfCases")
    void preservesLiteralAdfParagraphs(String source, String expected) {
        var text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", source);
        var content = new JsonArray();
        content.add(text);
        var paragraph = new JsonObject();
        paragraph.addProperty("type", "paragraph");
        paragraph.add("content", content);
        var blocks = new JsonArray();
        blocks.add(paragraph);
        var doc = new JsonObject();
        doc.addProperty("type", "doc");
        doc.addProperty("version", 1);
        doc.add("content", blocks);

        assertThat(JiraDescriptionMarkdown.render(doc, true)).isEqualTo(expected);
    }

    static Stream<Arguments> adfCases() {
        return Stream.of(
                Arguments.of("# Not a heading", "\\# Not a heading"),
                Arguments.of("- Not a list", "\\- Not a list"),
                Arguments.of("1. ordinary text", "1\\. ordinary text"),
                Arguments.of("sb-test-1", "sb-test-1"));
    }

    @Test
    void preservesLiteralBackslashWithoutCreatingHardBreak() throws Exception {
        var wiki = JiraMarkdown.toJira("C:\\\\tools", false);

        assertThat(wiki.getAsString()).isEqualTo("C:{{\\}}tools");
        var rendered = JiraDescriptionMarkdown.render(wiki, false);
        assertThat(visible(MARKDOWN.parse(rendered))).isEqualTo("C:\\tools");
        assertThat(rendered).doesNotContain("\n");
    }

    @Test
    void retainsOriginalWikiForLosslessExportAndNativeInput() throws Exception {
        var original = "sb-test-1\n{{ a }}\nfirst\\\\second";

        assertThat(JiraMarkdown.fromJira(new JsonPrimitive(original), false)).isEqualTo(original);
        assertThat(JiraMarkdown.toJira(original, false, JiraMarkdown.Format.wiki).getAsString())
                .isEqualTo(original);
    }

    private static String html(String markdown) {
        return HTML.render(MARKDOWN.parse(markdown));
    }

    private static String visible(Node node) {
        var result = new StringBuilder();
        if (node instanceof Text text) {
            result.append(text.getLiteral());
        } else if (node instanceof Code code) {
            result.append(code.getLiteral());
        }
        for (var child = node.getFirstChild(); child != null; child = child.getNext()) {
            result.append(visible(child));
        }
        return result.toString();
    }
}
