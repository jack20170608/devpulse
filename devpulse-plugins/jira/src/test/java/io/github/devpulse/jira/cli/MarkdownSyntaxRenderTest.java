package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.JiraMarkdown;
import io.github.devpulse.jira.MarkdownTemplateRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MarkdownSyntaxRenderTest {

    @Test
    void testSyntaxRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-simple.md"),
                "Missing template resource: issue-simple.md").toURI());
        var description = """
                # Level one
                ## Level two
                ### Level three
                #### Level four
                ##### Level five
                ###### Level six

                Setext one
                ==========

                Setext two
                ----------

                **Bold**, *italic*, and `inline_code`.

                - First item
                  - Nested bullet
                  - Another bullet
                - Second item
                  1. Nested ordered
                  2. Another ordered

                1. Ordered parent
                   - Nested child
                2. Ordered sibling

                > Important **note**.
                >
                > Another paragraph.

                ---

                First soft line
                Second soft line

                First hard line\s\s
                Second hard line\\
                Third hard line

                [Reference][docs], [docs][], and <https://example.com/help>.

                [docs]: https://example.com/docs

                ![Screenshot](https://example.com/screen.png)

                ```
                echo "$HOME"
                ```

                    if (ready) {
                        run();
                    }

                Literal \\*stars\\*, \\[brackets\\], and \\{code\\}.
                """.stripTrailing();
        var parameters = Map.of(
                "what", "Render common Markdown features",
                "why", "Verify combined formatting",
                "impact", "Affects issue descriptions",
                "description", description,
                "step1", "Open the issue",
                "step2", "Check formatting");
        var markdown = new MarkdownTemplateRenderer().render(template, parameters)
                .replace("\r\n", "\n");
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown).contains("### Description\n" + description + "\n\n### SIT");
        var expectedDescription = """
                h1. Level one

                h2. Level two

                h3. Level three

                h4. Level four

                h5. Level five

                h6. Level six

                h1. Setext one

                h2. Setext two

                *Bold*, _italic_, and {{inline_code}}.

                * First item
                ** Nested bullet
                ** Another bullet
                * Second item
                *# Nested ordered
                *# Another ordered

                # Ordered parent
                #* Nested child
                # Ordered sibling

                {quote}
                Important *note*.

                Another paragraph.
                {quote}

                ----

                First soft line
                Second soft line

                First hard line\\\\
                Second hard line\\\\
                Third hard line

                [Reference|https://example.com/docs], [docs|https://example.com/docs], and [https://example.com/help|https://example.com/help].

                !https://example.com/screen.png|alt=Screenshot!

                {code}
                echo "$HOME"
                {code}

                {code}
                if (ready) {
                    run();
                }
                {code}

                Literal \\*stars\\*, \\[brackets\\], and \\{code\\}.
                """.stripTrailing();
        assertThat(wiki).isEqualTo("""
                h3. What

                Render common Markdown features

                h3. Why

                Verify combined formatting

                h3. Impact

                Affects issue descriptions

                h3. Description

                """ + expectedDescription + """


                h3. SIT

                Please provide the steps to reproduce the issue in SIT environment.
                Step1: Open the issue
                Step2: Check formatting

                h3. UAT

                Please provide the steps to reproduce the issue in UAT environment.
                Step1: Open the issue
                Step2: Check formatting
                """.stripTrailing());

        for (var unsupported : new String[] {
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

        var adfMarkdown = """
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
        Files.writeString(outputDirectory.resolve("issue-syntax.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-syntax.wiki"),
                wiki, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-syntax.adf.json"),
                adf.toString(), StandardCharsets.UTF_8);
    }
}
