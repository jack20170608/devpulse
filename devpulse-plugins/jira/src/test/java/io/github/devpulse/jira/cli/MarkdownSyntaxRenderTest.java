package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.devpulse.jira.JiraMarkdown;
import io.github.devpulse.jira.MarkdownTemplateRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

public class MarkdownSyntaxRenderTest {

    @Test
    void testSyntaxRender() throws Exception {
        var template =
                Path.of(
                        Objects.requireNonNull(
                                        getClass().getResource("/golden/simple.input.md"),
                                        "Missing template resource: /golden/simple.input.md")
                                .toURI());
        var description = GoldenFiles.read("syntax.input.md").stripTrailing();
        var parameters =
                Map.of(
                        "what", "Render common Markdown features",
                        "why", "Verify combined formatting",
                        "impact", "Affects issue descriptions",
                        "description", description,
                        "step1", "Open the issue",
                        "step2", "Check formatting");
        var markdown = new MarkdownTemplateRenderer().render(template, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown).contains("### Description\n" + description + "\n\n### SIT");
        GoldenFiles.assertMatches("syntax", "wiki", "syntax.expected.wiki", wiki);

        for (var unsupported :
                new String[] {
                    "> Outer\n> > Inner",
                    "[Link](https://example.com \"Title\")",
                    "![Image](https://example.com/image.png \"Title\")",
                    "<div>HTML</div>",
                    "- First paragraph\n\n  Second paragraph",
                    "```\n{code}\n```"
                }) {
            assertThatThrownBy(() -> JiraMarkdown.toJira(unsupported, false))
                    .as("Reject unsafe Wiki conversion: %s", unsupported)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cannot be represented safely as Jira wiki");
        }

        var adfMarkdown =
                """
                Plain introduction.

                Plain **bold** *italic* `code`.

                - First
                - Second

                ```
                echo hello
                ```
                """;
        var adf = JiraMarkdown.toJira(adfMarkdown, true);
        assertThat(adf.getAsJsonObject().get("type").getAsString()).isEqualTo("doc");
        assertThat(adf.getAsJsonObject().getAsJsonArray("content")).hasSize(4);
        var roundTrip = JiraMarkdown.fromJira(adf, true);
        assertThat(roundTrip).contains("Plain introduction\\.", "**bold**", "*italic*", "`code`");
        assertThat(JiraMarkdown.toJira(roundTrip, true)).isEqualTo(adf);

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(
                outputDirectory.resolve("issue-syntax.md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve("issue-syntax.wiki"), wiki, StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve("issue-syntax.adf.json"),
                adf.toString(),
                StandardCharsets.UTF_8);
    }
}
