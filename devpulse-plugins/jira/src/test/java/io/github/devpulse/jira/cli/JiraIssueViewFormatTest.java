package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

class JiraIssueViewFormatTest {
    @Test
    void defaultsToMarkdownWithAllMetadataFields() throws Exception {
        var view = parse();

        assertThat(view.markdownFields()).containsExactly(
                "summary", "key", "issuetype", "status", "priority",
                "assignee", "reporter", "created", "updated", "epic", "story-point");
        assertThat(view.format).isEqualTo(JiraIssueOperations.View.OutputFormat.markdown);
    }

    @Test
    void selectsMetadataWithoutExplicitFormat() throws Exception {
        var view = parse("--fields", "summary,key,status");

        assertThat(view.markdownFields()).containsExactly("summary", "key", "status");
        assertThat(view.format).isEqualTo(JiraIssueOperations.View.OutputFormat.markdown);
    }

    @ParameterizedTest
    @MethodSource("legacyOptions")
    void preservesExplicitLegacyOutputOptions(List<String> options) throws Exception {
        var view = parse(options.toArray(String[]::new));

        assertThat(view.markdownFields()).isEmpty();
        assertThat(view.format).isEqualTo(JiraIssueOperations.View.OutputFormat.text);
    }

    static Stream<List<String>> legacyOptions() {
        return Stream.of(
                List.of("--format", "text"), List.of("--json"), List.of("--raw"),
                List.of("--plain"), List.of("--description-format", "raw"),
                List.of("--comments", "5"), List.of("--machine"));
    }

    @ParameterizedTest
    @MethodSource("conflictingOptions")
    void rejectsLegacyOptionsWithExplicitMarkdown(List<String> options) {
        var flags = new ArrayList<>(List.of("--format", "markdown"));
        flags.addAll(options);
        var view = parse(flags.toArray(String[]::new));

        assertThatThrownBy(view::markdownFields).isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be combined");
    }

    static Stream<List<String>> conflictingOptions() {
        return Stream.of(
                List.of("--json"), List.of("--raw"), List.of("--plain"),
                List.of("--description-format", "raw"), List.of("--comments", "5"));
    }

    @Test
    void retainsExplicitMarkdownInMachineMode() throws Exception {
        var view = parse("--machine", "--format", "markdown", "--fields", "key");

        assertThat(view.markdownFields()).containsExactly("key");
        assertThat(view.format).isEqualTo(JiraIssueOperations.View.OutputFormat.markdown);
    }

    @Test
    void keepsDefaultMarkdownWithZeroComments() throws Exception {
        var view = parse("--comments", "0");

        assertThat(view.markdownFields()).contains("summary", "key");
        assertThat(view.format).isEqualTo(JiraIssueOperations.View.OutputFormat.markdown);
    }

    private static JiraIssueOperations.View parse(String... options) {
        var cli = new CommandLine(new JiraCommand());
        var arguments = new ArrayList<>(List.of("issue", "view", "DEMO-1"));
        arguments.addAll(List.of(options));
        var parsed = cli.parseArgs(arguments.toArray(String[]::new));
        return (JiraIssueOperations.View) parsed.subcommand().subcommand().commandSpec().userObject();
    }
}
