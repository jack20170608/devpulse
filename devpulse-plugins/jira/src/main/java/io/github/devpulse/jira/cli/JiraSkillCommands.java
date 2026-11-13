package io.github.devpulse.jira.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "skill", mixinStandardHelpOptions = true,
        description = "Export bundled AI workflow instructions and templates.",
        subcommands = JiraSkillCommands.Export.class)
final class JiraSkillCommands implements Runnable {
    enum Language { en, zh }

    private static final String NAME = "jira-requirements";
    private static final List<Path> FILES = List.of(
            Path.of("SKILL.md"),
            Path.of("references", "workflow.md"),
            Path.of("references", "environment.md"),
            Path.of("templates", "specification.md"),
            Path.of("templates", "jira-description.md"));

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
            description = "Export the requirements-to-Jira skill; optionally capture a read-only Jira environment snapshot.")
    static final class Export extends JiraCommand.Action {
        @Option(names = "--output", required = true, paramLabel = "DIRECTORY",
                description = "New skill directory; existing paths are never overwritten.")
        Path output;
        @Option(names = "--templates", paramLabel = "DIRECTORY",
                description = "Override specification.md and/or jira-description.md from this directory. "
                        + "Otherwise use .jira/templates in the current directory; "
                        + "missing files fall back to bundled defaults.")
        Path templates;
        @Option(names = "--language", defaultValue = "en", paramLabel = "en|zh",
                description = "Language of bundled skill instructions and templates: "
                        + "${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). "
                        + "Custom templates are copied without translation.")
        Language language;
        @Option(names = "--with-environment",
                description = "Query Jira and include a dated project environment snapshot. "
                        + "Without this flag, export remains offline.")
        boolean withEnvironment;
        @Option(names = {"-p", "--project"}, paramLabel = "KEY_OR_ID",
                description = "Environment project; defaults to connection configuration. "
                        + "Requires --with-environment.")
        String project;

        @Override
        protected void execute() throws IOException {
            if (project != null && !withEnvironment) {
                throw JiraCommand.wrong("--project requires --with-environment.");
            }
            var destination = output.toAbsolutePath().normalize();
            if (destination.getParent() == null
                    || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw JiraCommand.wrong("Skill output must be a new directory; choose another path.");
            }
            var overrides = templates == null
                    ? Path.of(".jira", "templates") : templates;
            boolean present = Files.exists(overrides, LinkOption.NOFOLLOW_LINKS);
            if ((templates != null || present) && !Files.isDirectory(overrides)) {
                throw JiraCommand.wrong("Template override directory is missing or invalid.");
            }
            var contents = new LinkedHashMap<Path, String>();
            var sources = new JsonObject();
            for (var file : FILES) {
                var name = file.getFileName().toString();
                var source = overrides.resolve(name);
                boolean template = file.startsWith("templates");
                boolean overridden = template && present
                        && Files.exists(source, LinkOption.NOFOLLOW_LINKS);
                String content;
                if (overridden) {
                    if (!Files.isRegularFile(source) || Files.size(source) > 1024 * 1024) {
                        throw JiraCommand.wrong("Template override must be a UTF-8 file of at most 1 MiB.");
                    }
                    content = Files.readString(source, StandardCharsets.UTF_8);
                    if (content.isBlank()) {
                        throw JiraCommand.wrong("Template override must not be empty.");
                    }
                } else {
                    content = bundled(file, language);
                }
                contents.put(file, content);
                if (template) {
                    sources.addProperty(name, overridden
                            ? source.toAbsolutePath().normalize().toString() : "bundled");
                }
            }
            JsonObject environment = null;
            if (withEnvironment) {
                var connection = configuration();
                var selectedProject = JiraCatalogCommands.project(project, connection.project());
                var jira = client();
                environment = new JiraSkillEnvironment(connection, jira::request, jira::agile)
                        .collect(selectedProject);
                contents.put(Path.of("references", "environment.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(environment) + "\n");
            }
            Files.createDirectories(destination.getParent());
            var staging = Files.createTempDirectory(destination.getParent(), ".jira-skill-");
            try {
                for (var entry : contents.entrySet()) {
                    var target = staging.resolve(entry.getKey());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                }
                Files.move(staging, destination);
            } finally {
                for (var file : new java.util.ArrayList<>(contents.keySet()).reversed()) {
                    Files.deleteIfExists(staging.resolve(file));
                }
                Files.deleteIfExists(staging.resolve("templates"));
                Files.deleteIfExists(staging.resolve("references"));
                Files.deleteIfExists(staging);
            }
            var result = new JsonObject();
            result.addProperty("skill", NAME);
            result.addProperty("language", language.name());
            result.addProperty("directory", destination.toString());
            var files = new JsonArray();
            contents.keySet().forEach(file -> files.add(file.toString()));
            result.add("files", files);
            result.add("templateSources", sources);
            if (environment != null) {
                var summary = JiraSkillEnvironment.select(environment,
                        "capturedAt", "finishedAt", "project");
                for (var section : List.of("epics", "assignableUsers", "issueTypes", "boards", "sprints")) {
                    var values = environment.getAsJsonObject(section);
                    var status = JiraSkillEnvironment.select(values, "status", "complete", "reason", "support", "error");
                    status.addProperty("count", values.getAsJsonArray("items").size());
                    summary.add(section, status);
                }
                result.add("environment", summary);
            }
            machineData = result;
            print("Exported " + NAME + " (" + language + ") to " + destination + ".");
            if (environment != null) {
                var incomplete = new java.util.ArrayList<String>();
                for (var section : List.of("epics", "assignableUsers", "issueTypes", "boards", "sprints")) {
                    if (!environment.getAsJsonObject(section).get("complete").getAsBoolean()) {
                        incomplete.add(section);
                    }
                }
                if (!incomplete.isEmpty()) {
                    print("Environment snapshot is incomplete: " + String.join(", ", incomplete)
                            + ". See references\\environment.json for scope and query status.");
                }
            }
        }

        private static String bundled(Path file, Language language) throws IOException {
            boolean bilingual = file.equals(Path.of("templates", "specification.md"));
            var source = bilingual ? file : file.resolveSibling(
                    file.getFileName().toString().replace(".md", "." + language.name() + ".md"));
            var resource = "/skills/" + NAME + "/" + source.toString().replace('\\', '/');
            try (var stream = JiraSkillCommands.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw JiraCommand.wrong("Bundled Jira skill resource is missing.");
                }
                var content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
                if (!bilingual) {
                    return content;
                }
                var chinese = "\n## 中文\n";
                var english = "\n## English\n";
                int startChinese = content.indexOf(chinese);
                int startEnglish = content.indexOf(english);
                var heading = content.lines().filter(line -> line.startsWith("# "))
                        .findFirst().orElse("");
                var titles = heading.split(" / ", 2);
                if (startChinese < 0 || startEnglish <= startChinese || titles.length != 2) {
                    throw JiraCommand.wrong("Bundled Jira skill language sections are invalid.");
                }
                var title = language == Language.en ? "# " + titles[1] : titles[0];
                var body = language == Language.en
                        ? content.substring(startEnglish + english.length())
                        : content.substring(startChinese + chinese.length(), startEnglish);
                return title + "\n\n" + body.strip() + "\n";
            }
        }
    }
}
