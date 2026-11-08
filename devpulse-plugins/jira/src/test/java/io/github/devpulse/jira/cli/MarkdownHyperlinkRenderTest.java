package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.JiraMarkdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.devpulse.jira.MarkdownTemplateRenderer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MarkdownHyperlinkRenderTest {

    @Test
    void testHyperlinkRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-hyperlink.md"),
                "Missing template resource: issue-hyperlink.md").toURI());
        var parameters = Map.of(
                "what", "Fix issue hyperlink rendering",
                "why", "Keep reference links clickable",
                "impact", "Affects issue description links",
                "description", "See [issue details](https://example.com/issues/123)"
                        + " and [documentation](https://example.com/docs?view=full&lang=en#steps).",
                "sitTestSteps", List.of("Open the issue", "Follow the reference links"));

        var markdown = new MarkdownTemplateRenderer().render(template, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown.stripTrailing()).isEqualTo("""
                ### What
                Fix issue hyperlink rendering

                ### Why
                Keep reference links clickable

                ### Impact
                Affects issue description links

                ### Description
                See [issue details](https://example.com/issues/123) and [documentation](https://example.com/docs?view=full&lang=en#steps).

                ### SIT
                Please provide the steps to reproduce the issue in SIT environment.

                1. Open the issue
                2. Follow the reference links

                ### UAT
                Please provide the steps to reproduce the issue in UAT environment.
                1. Open the issue
                2. Follow the reference links
                """.stripTrailing());
        assertThat(wiki.stripTrailing()).isEqualTo("""
                h3. What

                Fix issue hyperlink rendering

                h3. Why

                Keep reference links clickable

                h3. Impact

                Affects issue description links

                h3. Description

                See [issue details|https://example.com/issues/123] and [documentation|https://example.com/docs?view=full&lang=en#steps].

                h3. SIT

                Please provide the steps to reproduce the issue in SIT environment.

                # Open the issue
                # Follow the reference links

                h3. UAT

                Please provide the steps to reproduce the issue in UAT environment.

                # Open the issue
                # Follow the reference links
                """.stripTrailing());

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-hyperlink.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-hyperlink.wiki"),
                wiki, StandardCharsets.UTF_8);
    }
}
