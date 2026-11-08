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

public class MarkdownCodeRenderTest {

    @Test
    void testCodeRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-code.md"),
                "Missing template resource: issue-code.md").toURI());
        var parameters = Map.of(
                "what", "Render reproduction code",
                "why", "Preserve code formatting",
                "impact", "Affects PVT and root cause instructions",
                "description", "Include Bash and Java code snippets.",
                "step1", "Open the issue",
                "step2", "Check the code snippets",
                "pvtCode", """
                        issue="DEV-123"
                        printf '%s\\n' "$issue"
                        java -version
                        """.stripTrailing());

        var markdown = new MarkdownTemplateRenderer().render(template, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown.stripTrailing()).isEqualTo("""
                ### What
                Render reproduction code

                ### Why
                Preserve code formatting

                ### Impact
                Affects PVT and root cause instructions

                ### Description
                Include Bash and Java code snippets.

                ### SIT
                Please provide the steps to reproduce the issue in SIT environment.
                Step1: Open the issue
                Step2: Check the code snippets


                ### UAT
                Please provide the steps to reproduce the issue in UAT environment.
                Step1: Open the issue
                Step2: Check the code snippets


                ### PVT
                Please refer to the below bash code to reproduce the issue in PVT environment.
                ```bash
                issue="DEV-123"
                printf '%s\\n' "$issue"
                java -version
                ```

                ### Root Cause
                This is the source code that produce this issue. Please refer to the below code snippet.

                ```java
                public static void main(String[] args) {
                    System.out.println("Hello World");
                }
                ```
                """.stripTrailing());
        assertThat(wiki.stripTrailing()).isEqualTo("""
                h3. What

                Render reproduction code

                h3. Why

                Preserve code formatting

                h3. Impact

                Affects PVT and root cause instructions

                h3. Description

                Include Bash and Java code snippets.

                h3. SIT

                Please provide the steps to reproduce the issue in SIT environment.
                Step1: Open the issue
                Step2: Check the code snippets

                h3. UAT

                Please provide the steps to reproduce the issue in UAT environment.
                Step1: Open the issue
                Step2: Check the code snippets

                h3. PVT

                Please refer to the below bash code to reproduce the issue in PVT environment.

                {code:bash}
                issue="DEV-123"
                printf '%s\\n' "$issue"
                java -version
                {code}

                h3. Root Cause

                This is the source code that produce this issue. Please refer to the below code snippet.

                {code:java}
                public static void main(String[] args) {
                    System.out.println("Hello World");
                }
                {code}
                """.stripTrailing());

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-code.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-code.wiki"),
                wiki, StandardCharsets.UTF_8);
    }
}
