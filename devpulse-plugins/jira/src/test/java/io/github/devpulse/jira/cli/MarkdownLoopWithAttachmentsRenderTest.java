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

public class MarkdownLoopWithAttachmentsRenderTest {

    public record TestStep(String description, String pic) {
        public String getDescription() {
            return description;
        }

        public String getPic() {
            return pic;
        }
    }

    @Test
    void testLoopWithAttachmentsRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
            getClass().getResource("/issue-loop-with-attachments.md"),
            "Missing template resource: issue-loop-with-attachments.md").toURI());
        List<Object> uatTestSteps = List.of(
            new TestStep("Open the **issue** and see the bird one",
                "![nice1](./bird1.png)"),
            new TestStep("Check the description",
                "![nice2](./bird2.png)"),
            new TestStep("Check the bird again",
                "![nice3](./bird3.png)"));
        var parameters = Map.of(
            "what", "Render steps with attachments",
            "why", "Show screenshots alongside reproduction steps",
            "impact", "Affects UAT instructions",
            "description", "Render object properties and image links.",
            "sitTestSteps", List.of("Open the issue", "Check the description"),
            "uatTestSteps", uatTestSteps);

        var markdown = new MarkdownTemplateRenderer().render(template, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown.stripTrailing()).isEqualTo("""
            ### What
            Render steps with attachments

            ### Why
            Show screenshots alongside reproduction steps

            ### Impact
            Affects UAT instructions

            ### Description
            Render object properties and image links.

            ### SIT
            Please provide the steps to reproduce the issue in SIT environment.

            Step1. Open the issue
            Step2. Check the description

            ### UAT
            Please provide the steps to reproduce the issue in UAT environment.
            Step1. Open the **issue** and see the bird one
            ![nice1](./bird1.png)
            Step2. Check the description
            ![nice2](./bird2.png)
            Step3. Check the bird again
            ![nice3](./bird3.png)
            """.stripTrailing());
        assertThat(wiki.stripTrailing()).isEqualTo("""
            h3. What

            Render steps with attachments

            h3. Why

            Show screenshots alongside reproduction steps

            h3. Impact

            Affects UAT instructions

            h3. Description

            Render object properties and image links.

            h3. SIT

            Please provide the steps to reproduce the issue in SIT environment.

            Step1. Open the issue
            Step2. Check the description

            h3. UAT

            Please provide the steps to reproduce the issue in UAT environment.
            Step1. Open the *issue* and see the bird one
            !./bird1.png|alt=nice1!
            Step2. Check the description
            !./bird2.png|alt=nice2!
            Step3. Check the bird again
            !./bird3.png|alt=nice3!
            """.stripTrailing());

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-loop-with-attachments.md"),
            markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-loop-with-attachments.wiki"),
            wiki, StandardCharsets.UTF_8);
    }
}
