package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.devpulse.jira.JiraMarkdown;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JiraIssueDocumentTest {
    @TempDir
    Path directory;

    private static final JsonArray FIELD_METADATA = JsonParser.parseString("""
            [{"id":"customfield_10010","name":"Epic Link",
              "schema":{"custom":"com.pyxis.greenhopper.jira:gh-epic-link"}},
             {"id":"customfield_10020","name":"Story Points"}]
            """).getAsJsonArray();

    @Test
    void readsMultilineYamlAndKeepsDescriptionSeparate() throws Exception {
        var document = JiraIssueDocument.parse("""
                ---
                summary: |-
                  First line
                  Second line
                key: DEMO-1
                updated: 2026-11-12T19:17:29.121-0500
                labels: [backend, "sb-test-1"]
                ---

                # Description

                sb-test-1
                ---
                Body thematic break.
                """);

        assertThat(document.string("summary")).isEqualTo("First line\nSecond line");
        assertThat(document.string("updated")).isEqualTo("2026-11-12T19:17:29.121-0500");
        assertThat(document.body()).isEqualTo("# Description\n\nsb-test-1\n---\nBody thematic break.\n");
    }

    @Test
    void preservesBomCrlfAndBodyWhitespace() throws Exception {
        var document = JiraIssueDocument.parse("\uFEFF---\r\nsummary: Title\r\n---\r\n\r\n  Text\r\n\r\n");

        assertThat(document.body()).isEqualTo("  Text\r\n\r\n");
        assertThat(JiraIssueDocument.parse(JiraIssueDocument.render(document.metadata(), document.body())))
                .isEqualTo(document);
    }

    @Test
    void templateCreatesOnlyOneReadableMarkdownFileAndNeverOverwrites() throws Exception {
        var command = new JiraCommand.IssueTemplate();
        var cli = new picocli.CommandLine(command);
        cli.setOut(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        var file = directory.resolve("issue.md");
        cli.parseArgs("--file", file.toString());

        command.execute();
        var document = JiraIssueDocument.read(file);

        assertThat(document.string("issuetype")).isEqualTo("Task");
        assertThat(document.fields(true, false, Set.of(), new JsonArray()).keySet())
                .containsExactlyInAnyOrder("project", "issuetype", "summary", "labels");
        assertThat(document.body()).contains("Replace this description.");
        assertThat(directory.resolve("issue.conf")).doesNotExist();
        var original = Files.readString(file);
        assertThatThrownBy(command::execute).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void rejectsLegacyFilesAndInvalidUtf8BeforeImporting() throws Exception {
        var legacy = directory.resolve("issue.conf");
        Files.writeString(legacy, "version=1\nfields { summary=Title }");
        var malformed = directory.resolve("issue.md");
        Files.write(malformed, new byte[]{(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> JiraIssueDocument.read(legacy)).isInstanceOf(IOException.class)
                .hasMessageContaining("HOCON issue files are no longer supported");
        assertThatThrownBy(() -> JiraIssueDocument.read(malformed)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> JiraIssueDocument.requireMarkdown(directory.resolve("issue.txt")))
                .isInstanceOf(IOException.class);
        JiraIssueDocument.requireMarkdown(directory.resolve("issue.MARKDOWN"));
    }

    @Test
    void roundTripsUnicodeEscapesExactDecimalsAndNestedBaselines() throws Exception {
        var metadata = JsonParser.parseString("""
                {"summary":"中文 \\"title\\" \\\\path\\nnext\\u0085\\u2028\\u2029",
                 "story-point":1.123456789012345678901234567890,
                 "customfield_10000":{"value":"yes","enabled":true,"values":[null,999999999999999999999]},
                 "originalFields":{"summary":"old","customfield_10000":1.123456789012345678901234567890}}
                """).getAsJsonObject();
        var body = "\n```\n---\n```\n\n";

        var result = JiraIssueDocument.parse(JiraIssueDocument.render(metadata, body));

        assertThat(result.metadata()).isEqualTo(metadata);
        assertThat(result.body()).isEqualTo(body);
        assertThat(result.metadata().get("story-point").getAsBigDecimal().toPlainString())
                .isEqualTo("1.123456789012345678901234567890");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "summary: Title", "---\nsummary: Title", "---\n[]\n---\n",
            "---\nsummary: one\nsummary: two\n---\n",
            "---\nx: {nested: 1, nested: 2}\n---\n",
            "---\nx: &value one\ny: *value\n---\n",
            "---\nx: &value [one]\ny: *value\n---\n",
            "---\nx: {<<: {summary: one}}\n---\n",
            "---\nx: !!java/object foo\n---\n",
            "---\nx: !!float .inf\n---\n",
            "---\nx: !!bool invalid\n---\n",
            "---\n[summary]: one\n---\n",
            "---\noriginalFields: []\n---\n",
            "---\nversion: 2\n---\n",
            "---\nsummary: [broken\n---\n"
    })
    void rejectsMalformedOrUnsafeDocuments(String source) {
        assertThatThrownBy(() -> JiraIssueDocument.parse(source)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsExcessiveSizeAndNesting() {
        assertThatThrownBy(() -> JiraIssueDocument.parse("---\nsummary: \"" + "a".repeat(1024 * 1024) + "\"\n---\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("1 MiB");
        assertThatThrownBy(() -> JiraIssueDocument.parse("---\nx: " + "[".repeat(65) + "0" + "]".repeat(65) + "\n---\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("64 levels");
    }

    @Test
    void ignoresEmptyFieldsButClearsExplicitNullAndLists() throws Exception {
        var fields = document("""
                summary: ""
                priority: null
                assignee: null
                labels: null
                components: []
                epic: ""
                story-point: ""
                """).fields(false, false, Set.of(), FIELD_METADATA);

        assertThat(fields.has("summary")).isFalse();
        assertThat(fields.get("priority")).isEqualTo(JsonNull.INSTANCE);
        assertThat(fields.get("assignee")).isEqualTo(JsonNull.INSTANCE);
        assertThat(fields.getAsJsonArray("labels")).isEmpty();
        assertThat(fields.getAsJsonArray("components")).isEmpty();
        assertThat(fields.has("parent")).isFalse();
        assertThat(fields.has("customfield_10020")).isFalse();
    }

    @Test
    void mapsViewMetadataAndNeverWritesReadOnlyFields() throws Exception {
        var fields = document("""
                summary: Title
                key: DEMO-1
                issuetype: Task
                priority: Medium
                assignee: jack
                reporter: Other User
                status: In Review
                created: 2026-11-01
                updated: 2026-11-12
                project: DEMO
                epic: DEMO-10
                story-point: 3.5
                """).fields(false, true, Set.of(), FIELD_METADATA);

        assertThat(fields.keySet()).containsExactlyInAnyOrder(
                "summary", "issuetype", "priority", "assignee", "parent", "customfield_10020");
        assertThat(fields.getAsJsonObject("issuetype").get("name").getAsString()).isEqualTo("Task");
        assertThat(fields.getAsJsonObject("assignee").get("accountId").getAsString()).isEqualTo("jack");
        assertThat(fields.getAsJsonObject("parent").get("key").getAsString()).isEqualTo("DEMO-10");
        assertThat(fields.get("customfield_10020").getAsBigDecimal()).isEqualByComparingTo("3.5");
    }

    @Test
    void mapsDcUsersEpicsAndNamedLists() throws Exception {
        var fields = document("""
                project: DEMO
                assignee: jack
                epic: DEMO-10
                components: [Backend, {id: "12"}]
                fixVersions: [v1]
                versions: [v0]
                """).fields(true, false, Set.of(), FIELD_METADATA);

        assertThat(fields.getAsJsonObject("project").get("key").getAsString()).isEqualTo("DEMO");
        assertThat(fields.getAsJsonObject("assignee").get("name").getAsString()).isEqualTo("jack");
        assertThat(fields.get("customfield_10010").getAsString()).isEqualTo("DEMO-10");
        assertThat(fields.getAsJsonArray("components").get(0).getAsJsonObject().get("name").getAsString())
                .isEqualTo("Backend");
        assertThat(fields.getAsJsonArray("components").get(1).getAsJsonObject().get("id").getAsString())
                .isEqualTo("12");
    }

    @Test
    void cliOverridesInvalidOrNullDocumentValuesBeforeMapping() throws Exception {
        var fields = document("""
                summary: null
                issuetype: [invalid]
                assignee: {displayName: "Not an identity"}
                epic: MISSING-1
                story-point: invalid
                labels: [from-document]
                customfield_10000: old
                """).fields(true, true, Set.of("summary", "issuetype", "assignee", "epic",
                        "story-point", "labels", "customfield_10000"), new JsonArray());

        assertThat(fields.keySet()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary: null", "issuetype: null", "project: null",
            "summary: 123", "labels: text", "labels: [null]", "priority: []",
            "assignee: {displayName: Jack}", "story-point: text", "unknown: value",
            "description: text", "components: text",
            "epic: DEMO-1\nparent: DEMO-2",
            "epic: DEMO-1\ncustomfield_10010: DEMO-2",
            "story-point: 2\ncustomfield_10020: 3"})
    void rejectsInvalidWritableMetadata(String yaml) {
        assertThatThrownBy(() -> document(yaml).fields(true, false, Set.of(), FIELD_METADATA))
                .isInstanceOf(IOException.class);
    }

    @Test
    void nullAliasesStillRequireFieldDiscovery() throws Exception {
        var document = document("epic: null\nstory-point: null");

        assertThat(document.needsMetadata(false, Set.of())).isTrue();
        var fields = document.fields(false, false, Set.of(), FIELD_METADATA);
        assertThat(fields.get("customfield_10010")).isEqualTo(JsonNull.INSTANCE);
        assertThat(fields.get("customfield_10020")).isEqualTo(JsonNull.INSTANCE);
        assertThat(document.needsMetadata(false, Set.of("epic", "story-point"))).isFalse();
    }

    @Test
    void recognizesLocalizedStoryPointsWithoutGuessingNumericFields() throws Exception {
        var metadata = JsonParser.parseString("""
                [{"id":"customfield_10020","name":"故事点",
                  "schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}},
                 {"id":"customfield_10021","name":"Budget",
                  "schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}}]
                """).getAsJsonArray();

        assertThat(JiraAgileCommands.storyPointsField(metadata)).isEqualTo("customfield_10020");
        assertThat(document("story-point: 5").fields(true, false, Set.of(), metadata)
                .get("customfield_10020").getAsInt()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"故事点估算\"}",
            "{\"name\":\"团队估算\",\"untranslatedName\":\"Story Points\"}",
            "{\"name\":\"团队估算\",\"untranslatedName\":\"Story point estimate\"}",
            "{\"name\":null,\"untranslatedName\":\"Story Points\"}"
    })
    void recognizesStoryPointsByLocalizedOrUntranslatedName(String properties) throws Exception {
        var field = JsonParser.parseString(properties).getAsJsonObject();
        field.addProperty("id", "customfield_10020");
        var metadata = new JsonArray();
        metadata.add(field);

        assertThat(JiraAgileCommands.storyPointsField(metadata)).isEqualTo("customfield_10020");
    }

    @Test
    void rejectsAmbiguousLocalizedStoryPointsFields() {
        var metadata = JsonParser.parseString("""
                [{"id":"customfield_10020","name":"故事点"},
                 {"id":"customfield_10021","name":"Estimate","untranslatedName":"Story Points"}]
                """).getAsJsonArray();

        assertThatThrownBy(() -> JiraAgileCommands.storyPointsField(metadata))
                .isInstanceOf(IOException.class).hasMessageContaining("ambiguous");
    }

    @Test
    void doesNotInferStoryPointsFromNumericSchemaOrSystemFieldNames() throws Exception {
        var metadata = JsonParser.parseString("""
                [{"id":"customfield_10021","name":"Budget","untranslatedName":null,
                  "schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}},
                 {"id":"timeestimate","name":"Story Points"}]
                """).getAsJsonArray();

        assertThat(JiraAgileCommands.storyPointsField(metadata)).isNull();
    }

    @Test
    void rejectsMissingAndAmbiguousDynamicFields() throws Exception {
        var document = document("story-point: 3");
        var ambiguous = FIELD_METADATA.deepCopy();
        ambiguous.add(JsonParser.parseString("{\"id\":\"customfield_999\",\"name\":\"Story point estimate\"}"));

        assertThatThrownBy(() -> document.fields(false, true, Set.of(), new JsonArray()))
                .isInstanceOf(IOException.class).hasMessageContaining("Story Points");
        assertThatThrownBy(() -> document.fields(false, true, Set.of(), ambiguous))
                .isInstanceOf(IOException.class).hasMessageContaining("ambiguous");
    }

    @Test
    void customCliValueOverridesTheCorrespondingAlias() throws Exception {
        var fields = document("story-point: invalid\nepic: DEMO-10")
                .fields(false, false, Set.of("customfield_10020", "customfield_10010"), FIELD_METADATA);

        assertThat(fields.keySet()).isEmpty();
    }

    @Test
    void usesExportedFieldBaselinesWithoutTreatingOmissionAsClearing() throws Exception {
        var document = document("""
                summary: Changed
                priority: Medium
                customfield_10000: ""
                originalFields: {summary: Original, priority: Medium, customfield_10000: old, environment: keep}
                """);

        var fields = document.fields(false, true, Set.of(), FIELD_METADATA);

        assertThat(fields.keySet()).containsExactly("summary");
        assertThat(document.originalFields().get("environment").getAsString()).isEqualTo("keep");
    }

    @Test
    void exportUsesWritableIdentitiesAndKeepsCustomStructures() {
        var source = JsonParser.parseString("""
                {"project":{"id":"1","key":"DEMO"},"issuetype":{"id":"2","name":"Task"},
                 "assignee":{"accountId":"account","name":"jack","displayName":"Jack Doe"},
                 "components":[{"id":"3","name":"Backend"}],
                 "customfield_10000":{"nested":[1,{"value":"keep"}]}}
                """).getAsJsonObject();

        var cloud = JiraIssueDocument.exportFields(source, true);
        var dc = JiraIssueDocument.exportFields(source, false);

        assertThat(cloud.get("assignee").getAsString()).isEqualTo("account");
        assertThat(dc.get("assignee").getAsString()).isEqualTo("jack");
        assertThat(cloud.getAsJsonArray("components").get(0).getAsString()).isEqualTo("Backend");
        assertThat(cloud.get("customfield_10000")).isEqualTo(source.get("customfield_10000"));
        assertThat(source.get("assignee").isJsonObject()).isTrue();
    }

    @Test
    void preservesUnchangedWikiExportsEvenWhenPresentationWouldBeLossy() throws Exception {
        var wiki = "{custom-macro}\noriginal\n{custom-macro}\n";
        var metadata = new JsonObject();
        metadata.addProperty("bodyFormat", "wiki");
        metadata.addProperty("descriptionSha256", JiraCommand.hash(wiki));
        var document = JiraIssueDocument.parse(JiraIssueDocument.render(metadata, wiki));

        assertThat(document.changedBody(null, null)).isNull();
        assertThat(document.changedBody(JiraMarkdown.Format.markdown, null)).isEqualTo(wiki);
        assertThat(document.bodyFormat()).isEqualTo(JiraMarkdown.Format.wiki);
    }

    @Test
    void detectsBodyChangesAndIgnoresEmptyBodyUnlessExplicitlyCleared() throws Exception {
        var document = document("descriptionSha256: \"" + JiraCommand.hash("Original") + "\"");
        var modified = new JiraIssueDocument(document.metadata(), "Changed\n");

        assertThat(modified.changedBody(null, null)).isEqualTo("Changed\n");
        assertThat(document.changedBody(null, null)).isNull();
        assertThat(document("description: null").fields(false, true, Set.of(), FIELD_METADATA)
                .get("description")).isEqualTo(JsonNull.INSTANCE);
        assertThatThrownBy(() -> new JiraIssueDocument(document("description: null").metadata(), "Text")
                .fields(false, true, Set.of(), FIELD_METADATA)).isInstanceOf(IOException.class);
    }

    @Test
    void unchangedViewBodyDoesNotOverwriteRemoteRichText() throws Exception {
        var document = new JiraIssueDocument(new JsonObject(), "# Readable text\n\n");

        assertThat(document.changedBody(null, "# Readable text")).isNull();
        assertThat(document.changedBody(null, "# Different text")).isEqualTo("# Readable text\n\n");
    }

    @Test
    void checksKeyModeApiAndTimestampEvenWithCliOverrides() throws Exception {
        var document = document("""
                key: DEMO-1
                updated: original
                mode: cloud
                apiVersion: 3
                descriptionSha256: hash
                originalFields: {summary: Original}
                """);

        document.requireBaseline();
        document.checkTarget("DEMO-1", "original", true, 3, false);
        document.checkTarget("DEMO-1", "new", true, 3, true);
        assertThatThrownBy(() -> document.checkTarget("DEMO-2", "original", true, 3, true))
                .isInstanceOf(IOException.class).hasMessageContaining("key");
        assertThatThrownBy(() -> document.checkTarget("DEMO-1", "original", false, 3, true))
                .isInstanceOf(IOException.class).hasMessageContaining("mode");
        assertThatThrownBy(() -> document.checkTarget("DEMO-1", "original", true, 2, true))
                .isInstanceOf(IOException.class).hasMessageContaining("API");
        assertThatThrownBy(() -> document.checkTarget("DEMO-1", "new", true, 3, false))
                .isInstanceOf(IOException.class).hasMessageContaining("changed since export");
        assertThatThrownBy(() -> document("key: DEMO-1").requireBaseline())
                .isInstanceOf(IOException.class).hasMessageContaining("jira export");
    }

    @Test
    void ignoresCreateTargetAndBaselinesButProtectsRequiredFieldsForEdit() throws Exception {
        var document = document("""
                key: DEMO-1
                summary: Title
                originalFields: {summary: Title}
                """);

        assertThat(document.fields(true, true, Set.of(), FIELD_METADATA).keySet()).containsExactly("summary");
        assertThatThrownBy(() -> document("summary: null").fields(false, true, Set.of(), FIELD_METADATA))
                .isInstanceOf(IOException.class).hasMessageContaining("Cannot clear");
    }

    @Test
    void recognizesUnchangedNamedValuesAndAbsentClearedFields() {
        var current = JsonParser.parseString("{\"id\":\"1\",\"name\":\"Task\"}");
        var proposed = JsonParser.parseString("{\"name\":\"Task\"}");

        assertThat(JiraIssueEditCommand.unchanged("issuetype", proposed, current)).isTrue();
        assertThat(JiraIssueEditCommand.unchanged("assignee", JsonNull.INSTANCE, null)).isTrue();
        assertThat(JiraIssueEditCommand.unchanged("issuetype",
                JsonParser.parseString("{\"name\":\"Bug\"}"), current)).isFalse();
    }

    private static JiraIssueDocument document(String yaml) throws IOException {
        return JiraIssueDocument.parse("---\n" + yaml.stripTrailing() + "\n---\n\n");
    }
}
