package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.MarkdownTemplateRenderer;
import io.github.devpulse.jira.JiraMarkdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MarkdownTableRenderTest {

    @Test
    void testTableRender() throws Exception {
        var markdownTemplate = Path.of(Objects.requireNonNull(
                getClass().getResource("/issue-table.md"),
                "Missing template resource: issue-table.md").toURI());
        var parameters = Map.of("results", List.of(
                Map.of("environment", "SIT",
                        "expected", "Issue opens",
                        "actual", "Issue opens",
                        "status", "PASS"),
                Map.of("environment", "UAT",
                        "expected", "Description displayed",
                        "actual", "Description missing",
                        "status", "FAIL"),
                Map.of("environment", "PVT",
                        "expected", "Code runs",
                        "actual", "Code runs",
                        "status", "PASS")));
        var renderer = new MarkdownTemplateRenderer();
        var markdown = renderer.render(markdownTemplate, parameters);
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        assertThat(markdown.replace("\r\n", "\n").stripTrailing()).isEqualTo("""
                [中文](#中文) | [English](#english)

                ## 中文

                ### 验证结果

                | 序号 | 环境 | 预期结果 | 实际结果 | 状态 |
                | --- | --- | --- | --- | --- |
                | 1 | SIT | Issue opens | Issue opens | PASS |
                | 2 | UAT | Description displayed | Description missing | FAIL |
                | 3 | PVT | Code runs | Code runs | PASS |

                ## English

                ### Verification results

                | No. | Environment | Expected result | Actual result | Status |
                | --- | --- | --- | --- | --- |
                | 1 | SIT | Issue opens | Issue opens | PASS |
                | 2 | UAT | Description displayed | Description missing | FAIL |
                | 3 | PVT | Code runs | Code runs | PASS |
                """.stripTrailing());
        assertThat(wiki.replace("\r\n", "\n").stripTrailing()).isEqualTo("""
                [中文|#中文] \\| [English|#english]

                h2. 中文

                h3. 验证结果

                ||序号||环境||预期结果||实际结果||状态||
                |1|SIT|Issue opens|Issue opens|PASS|
                |2|UAT|Description displayed|Description missing|FAIL|
                |3|PVT|Code runs|Code runs|PASS|

                h2. English

                h3. Verification results

                ||No.||Environment||Expected result||Actual result||Status||
                |1|SIT|Issue opens|Issue opens|PASS|
                |2|UAT|Description displayed|Description missing|FAIL|
                |3|PVT|Code runs|Code runs|PASS|
                """.stripTrailing());

        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(outputDirectory.resolve("issue-table.md"),
                markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("issue-table.wiki"),
                wiki, StandardCharsets.UTF_8);
    }
}
