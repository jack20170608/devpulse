package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.devpulse.jira.JiraMarkdown;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class MarkdownTableConversionTest {

    @Test
    void testTableConversion() throws Exception {
        var markdown = GoldenFiles.read("table-conversion.input.md");

        GoldenFiles.assertMatches(
                "table-conversion",
                "wiki",
                "table-conversion.expected.wiki",
                JiraMarkdown.toJira(markdown, false).getAsString());
        assertThat(
                        JiraMarkdown.toJira(
                                        """
                                        Name | Status
                                        --- | ---
                                        """,
                                        false)
                                .getAsString())
                .isEqualTo("||Name||Status||");
        assertThatThrownBy(() -> JiraMarkdown.toJira(markdown, true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ADF table uploads are not supported");
        assertThatThrownBy(() ->
                        JiraMarkdown.toJira(
                                """
                                | Name | Details |
                                | --- | --- |
                                | SIT | <br> |
                                """,
                                false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be represented safely as Jira wiki");
        assertThat(
                        JiraMarkdown.toJira(
                                        """
                                        ```text
                                        | Name | Status |
                                        | --- | --- |
                                        ```
                                        """,
                                        false)
                                .getAsString())
                .isEqualTo(
                        """
                        {code:text}
                        | Name | Status |
                        | --- | --- |
                        {code}
                        """
                                .stripTrailing());
        var nativeWiki = "||Name||Status||\n|SIT|PASS|";
        assertThat(JiraMarkdown.toJira(nativeWiki, false, JiraMarkdown.Format.wiki).getAsString())
                .isEqualTo(nativeWiki);
    }
}
