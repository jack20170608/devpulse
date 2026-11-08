package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.JiraMarkdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.devpulse.jira.MarkdownTemplateRenderer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MarkdownLoopRenderTest {

    @Test
    void testLoopRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-loop.md"),
                "Missing template resource: issue-loop.md").toURI());
        var parameters = new HashMap<String, Object>(Map.of(
                "what", "Fix issue details rendering",
                "why", "Keep issue descriptions readable",
                "impact", "Affects issue description display",
                "description", "Render **Markdown** and Jira wiki from one template.",
                "sitTestSteps", List.of(
                        "Open the issue",
                        "Check the **description**",
                        "Verify the result"),
                "uatTestSteps", List.of(
                        "Open the issue",
                        "Check the **description**",
                        "Verify the result")
        ));
        var renderer = new MarkdownTemplateRenderer();
        var markdown = renderer.render(template, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        var markdownHeader = """
                ### What
                Fix issue details rendering

                ### Why
                Keep issue descriptions readable

                ### Impact
                Affects issue description display

                ### Description
                Render **Markdown** and Jira wiki from one template.

                """;
        var wikiHeader = """
                h3. What

                Fix issue details rendering

                h3. Why

                Keep issue descriptions readable

                h3. Impact

                Affects issue description display

                h3. Description

                Render *Markdown* and Jira wiki from one template.

                """;
        assertThat(markdown.stripTrailing()).isEqualTo((markdownHeader + """
                ### SIT
                Please provide the steps to reproduce the issue in SIT environment.

                1. Open the issue
                2. Check the **description**
                3. Verify the result

                ### UAT
                Please provide the steps to reproduce the issue in UAT environment.
                1. Open the issue
                2. Check the **description**
                3. Verify the result
                """).stripTrailing());
        assertThat(wiki.stripTrailing()).isEqualTo((wikiHeader + """
                h3. SIT

                Please provide the steps to reproduce the issue in SIT environment.

                # Open the issue
                # Check the *description*
                # Verify the result

                h3. UAT

                Please provide the steps to reproduce the issue in UAT environment.

                # Open the issue
                # Check the *description*
                # Verify the result
                """).stripTrailing());

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-loop.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-loop.wiki"),
                wiki, StandardCharsets.UTF_8);

        parameters.put("sitTestSteps", List.of());
        var emptyMarkdown = renderer.render(template, parameters);
        assertThat(emptyMarkdown.stripTrailing()).isEqualTo((markdownHeader + """
                ### SIT
                Please provide the steps to reproduce the issue in SIT environment.


                ### UAT
                Please provide the steps to reproduce the issue in UAT environment.
                """).stripTrailing());
        assertThat(JiraMarkdown.toJira(emptyMarkdown, false).getAsString()
                .stripTrailing()).isEqualTo((wikiHeader + """
                h3. SIT

                Please provide the steps to reproduce the issue in SIT environment.

                h3. UAT

                Please provide the steps to reproduce the issue in UAT environment.
                """).stripTrailing());
    }

}
