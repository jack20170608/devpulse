package io.github.devpulse.jira.cli;

import io.github.devpulse.jira.JiraMarkdown;
import io.github.devpulse.jira.MarkdownTemplateRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden-file snapshots for the Jira markdown renderer.
 *
 * <p>Every case renders {@code golden/<name>.input.md} with the parameters below and compares the
 * resulting markdown and Jira wiki with {@code golden/<name>.expected.md} and {@code
 * golden/<name>.expected.wiki}. Run with {@code -Dgolden.update=true} to regenerate the expected
 * files after an intentional rendering change.
 */
public class GoldenMarkdownTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesGoldenFiles(GoldenCase goldenCase) throws Exception {
        var template =
                Path.of(
                        Objects.requireNonNull(
                                        GoldenMarkdownTest.class.getResource(goldenCase.template()),
                                        "Missing template resource: " + goldenCase.template())
                                .toURI());
        var markdown = new MarkdownTemplateRenderer().render(template, goldenCase.parameters());
        if (goldenCase.normalizeLineEndings()) {
            markdown = markdown.replace("\r\n", "\n");
        }
        var wiki = JiraMarkdown.toJira(markdown, false).getAsString();

        GoldenFiles.assertMatches(
                goldenCase.name(), "markdown", goldenCase.name() + ".expected.md", markdown);
        GoldenFiles.assertMatches(
                goldenCase.name(), "wiki", goldenCase.name() + ".expected.wiki", wiki);
        if (goldenCase.renderedName() != null) {
            writeRendered(goldenCase.renderedName(), markdown, wiki);
        }
    }

    private static void writeRendered(String renderedName, String markdown, String wiki)
            throws IOException {
        var outputDirectory = Files.createDirectories(Path.of("target", "rendered"));
        Files.writeString(
                outputDirectory.resolve(renderedName + ".md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve(renderedName + ".wiki"), wiki, StandardCharsets.UTF_8);
    }

    static Stream<GoldenCase> cases() {
        return Stream.of(
                new GoldenCase(
                        "code",
                        "/golden/code.input.md",
                        Map.of(
                                "what", "Render reproduction code",
                                "why", "Preserve code formatting",
                                "impact", "Affects PVT and root cause instructions",
                                "description", "Include Bash and Java code snippets.",
                                "step1", "Open the issue",
                                "step2", "Check the code snippets",
                                "pvtCode",
                                        """
                                        issue="DEV-123"
                                        printf '%s\\n' "$issue"
                                        java -version
                                        """
                                                .stripTrailing()),
                        false,
                        "issue-code"),
                new GoldenCase(
                        "comprehensive",
                        "/golden/comprehensive.input.md",
                        Map.ofEntries(
                                Map.entry("title", "Issue description disappears after saving"),
                                Map.entry(
                                        "description",
                                        "Saving an issue clears the description when `body` is"
                                                + " null."),
                                Map.entry("warning", "Reproduce only in the test project."),
                                Map.entry(
                                        "impacts",
                                        List.of(
                                                Map.of(
                                                        "area",
                                                        "Issue editing",
                                                        "details",
                                                        List.of(
                                                                "Existing descriptions are lost",
                                                                "New issues are unaffected")),
                                                Map.of(
                                                        "area",
                                                        "Support",
                                                        "details",
                                                        List.of("Manual recovery is required")))),
                                Map.entry(
                                        "sitSteps",
                                        List.of(
                                                "Open an existing issue",
                                                "Change the title and save")),
                                Map.entry(
                                        "uatSteps",
                                        List.of(
                                                Map.of(
                                                        "description", "Open the **issue**",
                                                        "screenshot", "./before.png",
                                                        "caption", "Before saving"),
                                                Map.of(
                                                        "description",
                                                        "Save and verify the empty description",
                                                        "screenshot",
                                                        "./after.png",
                                                        "caption",
                                                        "After saving"),
                                                Map.of(
                                                        "description", "Record the issue key",
                                                        "screenshot", "",
                                                        "caption", ""))),
                                Map.entry(
                                        "results",
                                        List.of(
                                                Map.of(
                                                        "environment", "SIT",
                                                        "expected", "Description retained",
                                                        "actual", "Description empty",
                                                        "status", "FAIL"),
                                                Map.of(
                                                        "environment", "UAT",
                                                        "expected", "Description retained",
                                                        "actual", "Description empty",
                                                        "status", "FAIL"))),
                                Map.entry(
                                        "pvtCode", "issue=\"DEV-123\"\nprintf '%s\\n' \"$issue\""),
                                Map.entry(
                                        "rootCauseDescription",
                                        "The update overwrites the existing value without a null"
                                                + " check."),
                                Map.entry(
                                        "rootCauseCode",
                                        "if (body == null) {\n    issue.setDescription(\"\");\n}"),
                                Map.entry("runbookUrl", "https://example.com/runbook"),
                                Map.entry(
                                        "logs",
                                        List.of("Request accepted", "Description cleared"))),
                        true,
                        "issue-comprehensive"),
                new GoldenCase(
                        "hyperlink",
                        "/golden/hyperlink.input.md",
                        Map.of(
                                "what", "Fix issue hyperlink rendering",
                                "why", "Keep reference links clickable",
                                "impact", "Affects issue description links",
                                "description",
                                        "See [issue details](https://example.com/issues/123) and"
                                                + " [documentation](https://example.com/docs?view=full&lang=en#steps).",
                                "sitTestSteps",
                                        List.of("Open the issue", "Follow the reference links")),
                        false,
                        "issue-hyperlink"),
                new GoldenCase(
                        "loop",
                        "/golden/loop.input.md",
                        loopParameters(
                                List.of(
                                        "Open the issue",
                                        "Check the **description**",
                                        "Verify the result")),
                        false,
                        "issue-loop"),
                new GoldenCase(
                        "loop-empty-sit",
                        "/golden/loop.input.md",
                        loopParameters(List.of()),
                        false,
                        null),
                new GoldenCase(
                        "loop-with-attachments",
                        "/golden/loop-with-attachments.input.md",
                        Map.of(
                                "what", "Render steps with attachments",
                                "why", "Show screenshots alongside reproduction steps",
                                "impact", "Affects UAT instructions",
                                "description", "Render object properties and image links.",
                                "sitTestSteps", List.of("Open the issue", "Check the description"),
                                "uatTestSteps",
                                        List.of(
                                                new TestStep(
                                                        "Open the **issue** and see the bird one",
                                                        "![nice1](./bird1.png)"),
                                                new TestStep(
                                                        "Check the description",
                                                        "![nice2](./bird2.png)"),
                                                new TestStep(
                                                        "Check the bird again",
                                                        "![nice3](./bird3.png)"))),
                        false,
                        "issue-loop-with-attachments"),
                new GoldenCase(
                        "simple",
                        "/golden/simple.input.md",
                        Map.of(
                                "what", "Fix issue details rendering",
                                "why", "Keep issue descriptions readable",
                                "impact", "Affects issue description display",
                                "description",
                                        "Render **Markdown** and Jira wiki from one template.",
                                "step1", "Open the issue",
                                "step2", "Check the description"),
                        false,
                        "issue-simple"),
                new GoldenCase(
                        "table-render",
                        "/golden/table-render.input.md",
                        Map.of(
                                "results",
                                List.of(
                                        Map.of(
                                                "environment", "SIT",
                                                "expected", "Issue opens",
                                                "actual", "Issue opens",
                                                "status", "PASS"),
                                        Map.of(
                                                "environment", "UAT",
                                                "expected", "Description displayed",
                                                "actual", "Description missing",
                                                "status", "FAIL"),
                                        Map.of(
                                                "environment", "PVT",
                                                "expected", "Code runs",
                                                "actual", "Code runs",
                                                "status", "PASS"))),
                        true,
                        "issue-table"));
    }

    private static Map<String, Object> loopParameters(List<String> sitTestSteps) {
        return Map.of(
                "what", "Fix issue details rendering",
                "why", "Keep issue descriptions readable",
                "impact", "Affects issue description display",
                "description", "Render **Markdown** and Jira wiki from one template.",
                "sitTestSteps", sitTestSteps,
                "uatTestSteps",
                        List.of(
                                "Open the issue",
                                "Check the **description**",
                                "Verify the result"));
    }

    record GoldenCase(
            String name,
            String template,
            Map<String, Object> parameters,
            boolean normalizeLineEndings,
            String renderedName) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record TestStep(String description, String pic) {
        public String getDescription() {
            return description;
        }

        public String getPic() {
            return pic;
        }
    }
}
