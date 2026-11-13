package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.devpulse.jira.JiraMarkdown;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class JiraIssueTemplateInputTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("templateOptions")
    void acceptsCompleteTemplateAlongsideExplicitDescription(String operation, String option) throws Exception {
        var file = template();
        var action = parse(operation, option, file.toString(), "--desc", "CLI description",
                "--desc-format", "wiki", "--summary", "CLI summary");
        var path = action instanceof JiraCreateCommand create ? create.file : ((JiraIssueEditCommand) action).file;
        var body = action instanceof JiraCreateCommand create ? create.body : ((JiraIssueEditCommand) action).body;
        var format = action instanceof JiraCreateCommand create ? create.descriptionFormat : ((JiraIssueEditCommand) action).descriptionFormat;
        var document = JiraIssueDocument.readTemplate(path, stdin("unused"));

        assertThat(document.string("summary")).isEqualTo("Template summary");
        assertThat(document.string("project")).isEqualTo("DEMO");
        assertThat(document.body()).isEqualTo("Template description.\n");
        assertThat(body).isEqualTo("CLI description");
        assertThat(format.resolve(document, true)).isEqualTo(JiraMarkdown.Format.wiki);
        assertThat(document.metadata().get("summary").getAsString()).isEqualTo("Template summary");
    }

    static Stream<Arguments> templateOptions() {
        return Stream.of("create", "edit").flatMap(operation ->
                Stream.of("--template", "--file").map(option -> Arguments.of(operation, option)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "edit"})
    void rejectsDescriptionOnlyTemplatesBeforeContactingJira(String operation) throws Exception {
        var file = directory.resolve("description.md");
        Files.writeString(file, "Description without metadata.");
        var action = parse(operation, "--template", file.toString(), "--desc", "Override");

        assertThatThrownBy(action::execute).isInstanceOf(IOException.class)
                .hasMessageContaining("YAML frontmatter");
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "edit"})
    void rejectsTwoStdinSourcesBeforeReadingOrContactingJira(String operation) {
        var action = parse(operation, "--template", "-", "--desc", "-");

        assertThatThrownBy(action::execute).isInstanceOf(IOException.class)
                .hasMessageContaining("Cannot read both");
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "edit"})
    void rejectsObsoleteTemplateFormat(String operation) {
        assertThatThrownBy(() -> parse(operation, "--template", "issue.md", "--template-format", "markdown"))
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "edit"})
    void rejectsTwoNamesForTheSameTemplate(String operation) {
        assertThatThrownBy(() -> parse(operation, "--template", "one.md", "--file", "two.md"))
                .isInstanceOf(CommandLine.OverwrittenOptionException.class);
    }

    @Test
    void preservesTemplateFormatUnlessDescriptionOrFormatIsExplicitlyOverridden() throws Exception {
        var document = JiraIssueDocument.parse("""
                ---
                summary: Title
                bodyFormat: wiki
                ---

                *Wiki description*
                """);
        var format = new JiraCommand.DescriptionFormat();

        assertThat(format.resolve(document, false)).isEqualTo(JiraMarkdown.Format.wiki);
        assertThat(format.resolve(document, true)).isEqualTo(JiraMarkdown.Format.markdown);
        assertThat(format.resolve(null, true)).isEqualTo(JiraMarkdown.Format.markdown);
        format.format = JiraMarkdown.Format.markdown;
        assertThat(format.resolve(document, false)).isEqualTo(JiraMarkdown.Format.markdown);
        assertThat(document.string("summary")).isEqualTo("Title");
        assertThat(document.string("bodyFormat")).isEqualTo("wiki");
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "edit"})
    void retainsLegacyDescriptionOptionAliases(String operation) throws Exception {
        var action = parse(operation, "--body", "Legacy description", "--body-format", "wiki");
        var body = action instanceof JiraCreateCommand create ? create.body : ((JiraIssueEditCommand) action).body;
        var format = action instanceof JiraCreateCommand create ? create.descriptionFormat : ((JiraIssueEditCommand) action).descriptionFormat;

        assertThat(body).isEqualTo("Legacy description");
        assertThat(format.resolve(null, true)).isEqualTo(JiraMarkdown.Format.wiki);
    }

    @ParameterizedTest
    @MethodSource("duplicateDescriptionOptions")
    void rejectsConflictingPrimaryAndLegacyAliases(String operation, List<String> options) {
        assertThatThrownBy(() -> parse(operation, options.toArray(String[]::new)))
                .isInstanceOf(CommandLine.OverwrittenOptionException.class);
    }

    static Stream<Arguments> duplicateDescriptionOptions() {
        return Stream.of("create", "edit").flatMap(operation -> Stream.of(
                List.of("--desc", "One", "--body", "Two"),
                List.of("--desc-format", "markdown", "--body-format", "wiki"))
                .map(options -> Arguments.of(operation, options)));
    }

    @Test
    void keepsNonDescriptionCommandsOnBodyFormat() {
        var root = new CommandLine(new JiraCommand());
        var issue = root.getSubcommands().get("issue");
        for (var command : List.of(
                issue.getSubcommands().get("comment").getSubcommands().get("add"),
                issue.getSubcommands().get("worklog").getSubcommands().get("add"))) {
            assertThat(command.getCommandSpec().optionsMap())
                    .containsKey("--body-format").doesNotContainKey("--desc-format");
        }
        assertThat(root.getSubcommands().get("epic").getSubcommands().get("create")
                .getCommandSpec().optionsMap()).containsKeys("--desc", "--desc-format");
        assertThat(root.getSubcommands().get("update").getCommandSpec().optionsMap())
                .containsKeys("--desc-format", "--body-format");
    }

    @Test
    void bodyOverrideCanReplaceATemplateClearWithoutLosingMetadata() throws Exception {
        var document = JiraIssueDocument.parse("""
                ---
                summary: Title
                description: null
                ---
                """);
        var fields = document.fields(true, true, Set.of("description"), new com.google.gson.JsonArray());

        assertThat(fields.keySet()).containsExactly("summary");
        assertThat(JiraCommand.readBody("Replacement", stdin("unused"))).isEqualTo("Replacement");
    }

    @Test
    void explicitBodyStdinContainsOnlyTheDescription() throws Exception {
        var input = stdin("中文 description\n\nSecond paragraph.\n");

        assertThat(JiraCommand.readBody("-", input)).isEqualTo("中文 description\n\nSecond paragraph.\n");
        assertThat(input.available()).isZero();
    }

    @Test
    void omittedOrLiteralBodyNeverReadsStdin() throws Exception {
        var input = stdin("Do not read implicitly.");
        var available = input.available();

        assertThat(JiraCommand.readBody(null, input)).isNull();
        assertThat(JiraCommand.readBody("", input)).isEmpty();
        assertThat(JiraCommand.readBody("Literal", input)).isEqualTo("Literal");
        assertThat(input.available()).isEqualTo(available);
    }

    @Test
    void templateStdinIncludesMetadataAndDescription() throws Exception {
        var input = stdin("""
                ---
                project: DEMO
                issuetype: Task
                summary: Stdin title
                ---

                Stdin description.
                """);
        var document = JiraIssueDocument.readTemplate(Path.of("-"), input);

        assertThat(document.string("summary")).isEqualTo("Stdin title");
        assertThat(document.body()).isEqualTo("Stdin description.\n");
        assertThatThrownBy(() -> JiraIssueDocument.readTemplate(Path.of("-"), stdin("Plain description")))
                .isInstanceOf(IOException.class).hasMessageContaining("YAML frontmatter");
    }

    @Test
    void fileTemplatesDoNotConsumeStdin() throws Exception {
        var input = stdin("Do not consume.");
        var available = input.available();

        var document = JiraIssueDocument.readTemplate(template(), input);

        assertThat(document.string("summary")).isEqualTo("Template summary");
        assertThat(input.available()).isEqualTo(available);
    }

    @Test
    void rejectsOversizeOrInvalidUtf8TemplateStdin() {
        assertThatThrownBy(() -> JiraIssueDocument.readTemplate(Path.of("-"), stdin("a".repeat(1024 * 1024 + 1))))
                .isInstanceOf(IOException.class).hasMessageContaining("1 MiB");
        assertThatThrownBy(() -> JiraIssueDocument.readTemplate(Path.of("-"),
                new ByteArrayInputStream(new byte[]{(byte) 0xC3, (byte) 0x28})))
                .isInstanceOf(IOException.class);
    }

    private Path template() throws IOException {
        var file = directory.resolve("issue.md");
        Files.writeString(file, """
                ---
                project: DEMO
                issuetype: Task
                summary: Template summary
                ---

                Template description.
                """);
        return file;
    }

    private static ByteArrayInputStream stdin(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static JiraCommand.Action parse(String operation, String... options) {
        var cli = new CommandLine(new JiraCommand());
        var arguments = new ArrayList<>(List.of("issue", operation));
        if (operation.equals("edit")) {
            arguments.add("DEMO-1");
        }
        arguments.addAll(List.of(options));
        return (JiraCommand.Action) cli.parseArgs(arguments.toArray(String[]::new))
                .subcommand().subcommand().commandSpec().userObject();
    }
}
