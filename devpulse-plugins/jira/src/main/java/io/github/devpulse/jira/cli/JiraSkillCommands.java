package io.github.devpulse.jira.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraConfiguration;
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
            Path.of("templates", "jira-issue.md"));

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
            description = "Export the requirements-to-Jira skill offline; the skill reads the "
                    + "environment snapshot saved by jira env refresh.")
    static final class Export extends JiraCommand.Action {
        @Option(names = "--output", required = true, paramLabel = "DIRECTORY",
                description = "New skill directory; existing paths are never overwritten.")
        Path output;
        @Option(names = "--profile", paramLabel = "NAME",
                description = "Template profile (.jira/profiles/NAME, then ~/.jira/profiles/NAME). "
                        + "Otherwise use the configuration's profile, an interactive choice when "
                        + "several profiles exist, or default. Missing files use bundled templates.")
        String profile;
        @Option(names = "--no-input",
                description = "Never prompt for a profile.")
        boolean noInput;
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
            var selected = selectProfile();
            var contents = new LinkedHashMap<Path, String>();
            var sources = new JsonObject();
            for (var file : FILES) {
                var name = file.getFileName().toString();
                boolean template = file.startsWith("templates");
                var content = template && selected != null
                        ? JiraProfiles.read(selected.directory(), name) : null;
                boolean overridden = content != null;
                if (!overridden) {
                    content = bundled(file, language);
                }
                contents.put(file, content);
                if (template) {
                    sources.addProperty(name, overridden
                            ? JiraProfiles.file(selected.directory(), name)
                                    .toAbsolutePath().normalize().toString()
                            : "bundled");
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
            if (selected != null) {
                result.add("profile", selected.json());
            } else {
                result.add("profile", com.google.gson.JsonNull.INSTANCE);
            }
            result.add("templateSources", sources);
            machineData = result;
            print("Exported " + NAME + " (" + language + ") to " + destination
                    + (selected == null ? " using bundled templates."
                    : " using profile " + selected.name() + " (" + selected.scope() + ")."));
        }

        private JiraProfiles.Location selectProfile() throws IOException {
            var name = profile;
            boolean implicit = false;
            if (name == null) {
                var file = JiraConfiguration.find(explicitConfigFile());
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    name = JiraConfiguration.load(file).profile();
                }
            }
            if (name == null) {
                var available = JiraProfiles.names();
                var console = System.console();
                if (available.size() > 1 && !noInput && console != null) {
                    name = choose(available, console);
                } else {
                    name = JiraProfiles.DEFAULT;
                    implicit = true;
                }
            }
            var found = JiraProfiles.find(name);
            if (found.isPresent()) {
                return found.get();
            }
            if (implicit && JiraProfiles.names().isEmpty()) {
                return null;
            }
            throw JiraProfiles.missing(name);
        }

        private String choose(List<String> available, java.io.Console console) throws IOException {
            var err = spec.commandLine().getErr();
            for (int index = 0; index < available.size(); index++) {
                err.println("  " + (index + 1) + ") " + available.get(index));
            }
            int fallback = Math.max(0, available.indexOf(JiraProfiles.DEFAULT));
            while (true) {
                err.print("Profile [" + available.get(fallback) + "]: ");
                err.flush();
                var answer = console.readLine();
                if (answer == null) {
                    throw JiraCommand.wrong("Input ended; no profile was selected.");
                }
                answer = answer.strip();
                if (answer.isEmpty()) {
                    return available.get(fallback);
                }
                if (available.contains(answer)) {
                    return answer;
                }
                if (answer.matches("\\d{1,3}")) {
                    int choice = Integer.parseInt(answer);
                    if (choice >= 1 && choice <= available.size()) {
                        return available.get(choice - 1);
                    }
                }
                err.println("Choose a listed number or profile name.");
            }
        }

        static String bundled(Path file, Language language) throws IOException {
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
