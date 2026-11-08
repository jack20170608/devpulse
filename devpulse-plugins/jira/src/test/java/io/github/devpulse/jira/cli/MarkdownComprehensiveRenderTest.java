package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.JiraMarkdown;
import io.github.devpulse.jira.MarkdownTemplateRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MarkdownComprehensiveRenderTest {

    @Test
    void testComprehensiveRender() throws Exception {
        var template = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-comprehensive.md"),
                "Missing template resource: issue-comprehensive.md").toURI());
        var parameters = Map.ofEntries(
                Map.entry("title", "Issue description disappears after saving"),
                Map.entry("description", "Saving an issue clears the description when `body` is null."),
                Map.entry("warning", "Reproduce only in the test project."),
                Map.entry("impacts", List.of(
                        Map.of("area", "Issue editing",
                                "details", List.of("Existing descriptions are lost", "New issues are unaffected")),
                        Map.of("area", "Support",
                                "details", List.of("Manual recovery is required")))),
                Map.entry("sitSteps", List.of("Open an existing issue", "Change the title and save")),
                Map.entry("uatSteps", List.of(
                        Map.of("description", "Open the **issue**",
                                "screenshot", "./before.png", "caption", "Before saving"),
                        Map.of("description", "Save and verify the empty description",
                                "screenshot", "./after.png", "caption", "After saving"),
                        Map.of("description", "Record the issue key",
                                "screenshot", "", "caption", ""))),
                Map.entry("results", List.of(
                        Map.of("environment", "SIT", "expected", "Description retained",
                                "actual", "Description empty", "status", "FAIL"),
                        Map.of("environment", "UAT", "expected", "Description retained",
                                "actual", "Description empty", "status", "FAIL"))),
                Map.entry("pvtCode", "issue=\"DEV-123\"\nprintf '%s\\n' \"$issue\""),
                Map.entry("rootCauseDescription", "The update overwrites the existing value without a null check."),
                Map.entry("rootCauseCode", "if (body == null) {\n    issue.setDescription(\"\");\n}"),
                Map.entry("runbookUrl", "https://example.com/runbook"),
                Map.entry("logs", List.of("Request accepted", "Description cleared")));

        var markdown = new MarkdownTemplateRenderer().render(template, parameters)
                .replace("\r\n", "\n");
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();
        var expectedMarkdownReport = """
                ### Problem

                **Issue description disappears after saving**

                Saving an issue clears the description when `body` is null.

                > Reproduce only in the test project.

                ### Impact

                - Issue editing
                  - Existing descriptions are lost
                  - New issues are unaffected
                - Support
                  - Manual recovery is required

                ### Reproduction

                #### SIT

                1. Open an existing issue
                2. Change the title and save

                #### UAT

                1. Open the **issue**
                   ![Before saving](./before.png)
                2. Save and verify the empty description
                   ![After saving](./after.png)
                3. Record the issue key

                ### Verification

                | Environment | Expected | Actual | Status |
                | --- | --- | --- | --- |
                | SIT | Description retained | Description empty | FAIL |
                | UAT | Description retained | Description empty | FAIL |

                ### PVT

                ```bash
                issue="DEV-123"
                printf '%s\\n' "$issue"
                ```

                ### Root cause

                The update overwrites the existing value without a null check.

                ```java
                if (body == null) {
                    issue.setDescription("");
                }
                ```

                ---

                ### References

                [Runbook][runbook]

                [runbook]: https://example.com/runbook

                Request accepted\\
                Description cleared
                """.stripTrailing();
        assertThat(markdown.stripTrailing()).isEqualTo(
                "[中文](#中文) | [English](#english)\n\n## 中文\n\n"
                        + chineseHeadings(expectedMarkdownReport)
                        + "\n\n## English\n\n" + expectedMarkdownReport);

        var expectedWikiReport = """
                h3. Problem

                *Issue description disappears after saving*

                Saving an issue clears the description when {{body}} is null.

                {quote}
                Reproduce only in the test project.
                {quote}

                h3. Impact

                * Issue editing
                ** Existing descriptions are lost
                ** New issues are unaffected
                * Support
                ** Manual recovery is required

                h3. Reproduction

                h4. SIT

                # Open an existing issue
                # Change the title and save

                h4. UAT

                # Open the *issue*
                !./before.png|alt=Before saving!
                # Save and verify the empty description
                !./after.png|alt=After saving!
                # Record the issue key

                h3. Verification

                ||Environment||Expected||Actual||Status||
                |SIT|Description retained|Description empty|FAIL|
                |UAT|Description retained|Description empty|FAIL|

                h3. PVT

                {code:bash}
                issue="DEV-123"
                printf '%s\\n' "$issue"
                {code}

                h3. Root cause

                The update overwrites the existing value without a null check.

                {code:java}
                if (body == null) {
                    issue.setDescription("");
                }
                {code}

                ----

                h3. References

                [Runbook|https://example.com/runbook]

                Request accepted\\\\
                Description cleared
                """.stripTrailing();
        assertThat(wiki).isEqualTo(
                "[中文|#中文] \\| [English|#english]\n\nh2. 中文\n\n"
                        + chineseHeadings(expectedWikiReport)
                        + "\n\nh2. English\n\n" + expectedWikiReport);

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-comprehensive.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-comprehensive.wiki"),
                wiki, StandardCharsets.UTF_8);
    }

    private static String chineseHeadings(String report) {
        return report.replace("Problem\n", "问题说明\n")
                .replace("Impact\n", "影响范围\n")
                .replace("Reproduction\n", "复现步骤\n")
                .replace("Verification\n", "验证结果\n")
                .replace("Root cause\n", "根因分析\n")
                .replace("References\n", "参考资料\n");
    }
}
