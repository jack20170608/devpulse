package io.github.devpulse.jira.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
            Path.of("templates", "specification.md"),
            Path.of("templates", "jira-description.md"));

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
            description = "Export the requirements-to-Jira skill offline to a new directory.")
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

        @Override
        protected void execute() throws IOException {
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
                for (var file : FILES.reversed()) {
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
            FILES.forEach(file -> files.add(file.toString()));
            result.add("files", files);
            result.add("templateSources", sources);
            machineData = result;
            print("Exported " + NAME + " (" + language + ") to " + destination + ".");
        }

        private static String bundled(Path file, Language language) throws IOException {
            boolean skill = file.equals(Path.of("SKILL.md"));
            var source = skill ? "SKILL." + language.name() + ".md"
                    : file.toString().replace('\\', '/');
            var resource = "/skills/" + NAME + "/" + source;
            try (var stream = JiraSkillCommands.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw JiraCommand.wrong("Bundled Jira skill resource is missing.");
                }
                var content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
                if (skill) {
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
