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

@Command(
        name = "skill",
        mixinStandardHelpOptions = true,
        description = "Export bundled AI workflow instructions and templates.",
        subcommands = JiraSkillCommands.Export.class)
final class JiraSkillCommands implements Runnable {
    enum Language {
        en,
        zh
    }

    private static final String NAME = "jira-requirements";
    private static final List<Path> FILES =
            List.of(
                    Path.of("SKILL.md"),
                    Path.of("references", "workflow.md"),
                    Path.of("templates", "jira-issue.md"));

    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(
            name = "export",
            mixinStandardHelpOptions = true,
            description = "Export the requirements-to-Jira skill offline.")
    static final class Export extends JiraCommand.Action {
        @Option(
                names = "--output",
                paramLabel = "DIRECTORY",
                description =
                        "Parent directory for the skill (default: .claude/skills); "
                                + "writes its jira-requirements subdirectory. "
                                + "Cannot be combined with --global.")
        Path output;

        @Option(
                names = {"-g", "--global"},
                description =
                        "Export to ~/.claude/skills instead of the current directory's "
                                + ".claude/skills. Cannot be combined with --output.")
        boolean global;

        @Option(
                names = "--profile",
                paramLabel = "NAME",
                description =
                        "Template profile (.jira/profiles/NAME, then ~/.jira/profiles/NAME). "
                                + "Otherwise use the configuration's profile, an interactive choice when "
                                + "several profiles exist, or default. Missing files use bundled templates.")
        String profile;

        @Option(
                names = "--overwrite",
                description =
                        "Replace an existing jira-requirements directory without asking. "
                                + "Otherwise an interactive terminal asks for confirmation.")
        boolean overwrite;

        @Option(
                names = "--no-input",
                description = "Never prompt for a profile or overwrite confirmation.")
        boolean noInput;

        @Option(
                names = "--language",
                defaultValue = "en",
                paramLabel = "en|zh",
                description =
                        "Language of bundled skill instructions and templates: "
                                + "${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). "
                                + "Custom templates are copied without translation.")
        Language language;

        @Override
        protected void execute() throws IOException {
            if (global && output != null) {
                throw JiraCommand.wrong("Choose either --global or --output, not both.");
            }
            var parent =
                    (output != null
                                    ? output
                                    : global
                                            ? Path.of(
                                                    System.getProperty("user.home"),
                                                    ".claude",
                                                    "skills")
                                            : Path.of(".claude", "skills"))
                            .toAbsolutePath()
                            .normalize();
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent)) {
                throw JiraCommand.wrong("Skill output must be a directory: " + parent);
            }
            var destination = parent.resolve(NAME);
            boolean replace = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            if (replace) {
                if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw JiraCommand.wrong(
                            "Skill path exists and is not a directory: " + destination);
                }
                if (!overwrite && !confirmOverwrite(destination)) {
                    throw JiraCommand.wrong("Skill was not overwritten: " + destination);
                }
            }
            var selected = selectProfile();
            var contents = new LinkedHashMap<Path, String>();
            var sources = new JsonObject();
            for (var file : FILES) {
                var name = file.getFileName().toString();
                boolean template = file.startsWith("templates");
                var content =
                        template && selected != null
                                ? JiraProfiles.read(selected.directory(), name)
                                : null;
                boolean overridden = content != null;
                if (!overridden) {
                    content =
                            template
                                    ? JiraProfiles.presetTemplate(
                                            JiraProfiles.STANDARD, name, language)
                                    : bundled(file, language);
                }
                contents.put(file, content);
                if (template) {
                    sources.addProperty(
                            name,
                            overridden
                                    ? JiraProfiles.file(selected.directory(), name)
                                            .toAbsolutePath()
                                            .normalize()
                                            .toString()
                                    : "bundled");
                }
            }
            Files.createDirectories(parent);
            var staging = Files.createTempDirectory(parent, ".jira-skill-");
            try {
                for (var entry : contents.entrySet()) {
                    var target = staging.resolve(entry.getKey());
                    Files.createDirectories(target.getParent());
                    Files.writeString(
                            target,
                            entry.getValue(),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                }
                if (replace) {
                    var previous = parent.resolve(".jira-skill-old-" + System.nanoTime());
                    Files.move(destination, previous);
                    try {
                        Files.move(staging, destination);
                    } catch (IOException failure) {
                        Files.move(previous, destination);
                        throw failure;
                    }
                    deleteTree(previous);
                } else {
                    Files.move(staging, destination);
                }
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
            result.addProperty("overwritten", replace);
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
            print(
                    (replace ? "Replaced " : "Exported ")
                            + NAME
                            + " ("
                            + language
                            + ") to "
                            + destination
                            + (selected == null
                                    ? " using bundled templates."
                                    : " using profile "
                                            + selected.name()
                                            + " ("
                                            + selected.scope()
                                            + ")."));
        }

        private boolean confirmOverwrite(Path destination) throws IOException {
            var console = System.console();
            if (noInput || machine() || console == null) {
                throw JiraCommand.wrong(
                        "Skill already exists: "
                                + destination
                                + "; rerun with --overwrite to replace it.");
            }
            var err = spec.commandLine().getErr();
            err.print(
                    "Skill already exists: "
                            + destination
                            + "\nOverwrite it and remove all files in it? [y/N]: ");
            err.flush();
            var answer = console.readLine();
            if (answer == null) {
                throw JiraCommand.wrong("Input ended; skill was not overwritten.");
            }
            answer = answer.strip().toLowerCase(java.util.Locale.ROOT);
            return answer.equals("y") || answer.equals("yes");
        }

        private static void deleteTree(Path root) throws IOException {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
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
            var source =
                    file.resolveSibling(
                            file.getFileName()
                                    .toString()
                                    .replace(".md", "." + language.name() + ".md"));
            var resource = "/skills/" + NAME + "/" + source.toString().replace('\\', '/');
            try (var stream = JiraSkillCommands.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw JiraCommand.wrong("Bundled Jira skill resource is missing.");
                }
                var content =
                        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                                .replace("\r\n", "\n");
                return content;
            }
        }
    }
}
