package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.JiraMarkdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import io.github.devpulse.jira.MarkdownTemplateRenderer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MarkdownSimpleRenderTest {

    @Test
    void testSimpleRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-simple.md"),
                "Missing template resource: issue-simple.md").toURI());
        var parameters = Map.of(
                "what", "Fix issue details rendering",
                "why", "Keep issue descriptions readable",
                "impact", "Affects issue description display",
                "description", "Render **Markdown** and Jira wiki from one template.",
                "step1", "Open the issue",
                "step2", "Check the description");

        var markdown = new MarkdownTemplateRenderer().render(template, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown.stripTrailing()).isEqualTo("""
                ### What
                Fix issue details rendering

                ### Why
                Keep issue descriptions readable

                ### Impact
                Affects issue description display

                ### Description
                Render **Markdown** and Jira wiki from one template.

                ### SIT
                Please provide the steps to reproduce the issue in SIT environment.
                Step1: Open the issue
                Step2: Check the description


                ### UAT
                Please provide the steps to reproduce the issue in UAT environment.
                Step1: Open the issue
                Step2: Check the description
                """.stripTrailing());
        assertThat(wiki.stripTrailing()).isEqualTo("""
                h3. What

                Fix issue details rendering

                h3. Why

                Keep issue descriptions readable

                h3. Impact

                Affects issue description display

                h3. Description

                Render *Markdown* and Jira wiki from one template.

                h3. SIT

                Please provide the steps to reproduce the issue in SIT environment.
                Step1: Open the issue
                Step2: Check the description

                h3. UAT

                Please provide the steps to reproduce the issue in UAT environment.
                Step1: Open the issue
                Step2: Check the description
                """.stripTrailing());

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-simple.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-simple.wiki"),
                wiki, StandardCharsets.UTF_8);
    }
}
