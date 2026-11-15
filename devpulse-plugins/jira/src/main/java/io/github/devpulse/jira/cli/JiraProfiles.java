package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Template profiles: {@code .jira/profiles/NAME} in the current directory
 * shadows {@code ~/.jira/profiles/NAME}; missing files use bundled defaults.
 */
@Command(name = "profile", mixinStandardHelpOptions = true,
        description = "Manage template profiles used by bundled AI skills.",
        subcommands = {JiraProfiles.ListProfiles.class, JiraProfiles.Init.class})
final class JiraProfiles implements Runnable {
    static final String DEFAULT = "default";
    static final String STANDARD = "standard";
    static final String SKILL = "jira-requirements";
    static final List<String> TEMPLATES = List.of("jira-issue.md");
    static final List<String> PRESETS = List.of(STANDARD, "story", "bug", "spike", "agile");
    private static final long MAX_TEMPLATE = 1024 * 1024;

    record Location(String name, String scope, Path directory) {
        JsonObject json() {
            var result = new JsonObject();
            result.addProperty("name", name);
            result.addProperty("scope", scope);
            result.addProperty("directory", directory.toAbsolutePath().normalize().toString());
            return result;
        }
    }

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static Path root(boolean global) {
        return global ? JiraConfiguration.globalFile().getParent().resolve("profiles")
                : Path.of(".jira", "profiles");
    }

    static String name(String name) throws IOException {
        if (name == null || !name.matches(JiraConfiguration.PROFILE_NAME)) {
            throw wrong("Profile names use letters, digits, '-' or '_' (at most 64 characters).");
        }
        return name;
    }

    /** All profiles, local before global; a global entry may be shadowed by a local one. */
    static List<Location> list() throws IOException {
        var result = new ArrayList<Location>();
        for (var global : List.of(false, true)) {
            var root = root(global);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            var found = new TreeMap<String, Path>();
            try (var entries = Files.list(root)) {
                for (var entry : (Iterable<Path>) entries::iterator) {
                    var candidate = entry.getFileName().toString();
                    if (candidate.matches(JiraConfiguration.PROFILE_NAME)
                            && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        found.put(candidate, entry);
                    }
                }
            }
            found.forEach((candidate, directory) -> result.add(
                    new Location(candidate, global ? "global" : "local", directory)));
        }
        return result;
    }

    static Optional<Location> find(String name) throws IOException {
        name(name);
        return list().stream().filter(location -> location.name().equals(name)).findFirst();
    }

    static List<String> names() throws IOException {
        return list().stream().map(Location::name).distinct().toList();
    }

    static IOException missing(String name) throws IOException {
        var available = names();
        return wrong("Profile not found: " + name + ". Available: "
                + (available.isEmpty() ? "none (run jira init or jira profile init NAME)"
                : String.join(", ", available)) + ".");
    }

    /** Template content from a profile directory, or null when the profile lacks it. */
    static String read(Path profile, String template) throws IOException {
        var source = profile.resolve(SKILL).resolve(template);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) > MAX_TEMPLATE) {
            throw wrong("Profile template must be a regular UTF-8 file of at most 1 MiB: " + source);
        }
        var content = Files.readString(source, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw wrong("Profile template must not be empty: " + source);
        }
        return content;
    }

    static Path file(Path profile, String template) {
        return profile.resolve(SKILL).resolve(template);
    }

    /** Write missing profile templates from source or preset; never overwrite. */
    static List<Path> materialize(Path profile, Path source, String preset,
            JiraSkillCommands.Language language) throws IOException {
        var selectedPreset = preset(preset);
        var contents = new LinkedHashMap<Path, String>();
        for (var template : TEMPLATES) {
            var content = source == null ? null : read(source, template);
            contents.put(file(profile, template), content != null ? content
                    : presetTemplate(selectedPreset, template, language));
        }
        var created = new ArrayList<Path>();
        for (Map.Entry<Path, String> entry : contents.entrySet()) {
            if (Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.createDirectories(entry.getKey().getParent());
            Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            created.add(entry.getKey());
        }
        return created;
    }

    static String preset(String name) throws IOException {
        if (name == null || !PRESETS.contains(name)) {
            throw wrong("Unknown template preset: " + name + ". Available: "
                    + String.join(", ", PRESETS) + ".");
        }
        return name;
    }

    static String presetTemplate(String preset, String template,
            JiraSkillCommands.Language language) throws IOException {
        return JiraSkillCommands.Export.bundled(
                Path.of("presets", preset, "templates", template), language);
    }

    static JsonArray paths(List<Path> files) {
        var result = new JsonArray();
        files.forEach(file -> result.add(file.toAbsolutePath().normalize().toString()));
        return result;
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List local and global profiles; optionally include bundled presets.")
    static final class ListProfiles extends JiraCommand.Action {
        @Option(names = "--presets",
                description = "Also list built-in template presets.")
        boolean includePresets;

        @Override
        protected void execute() throws IOException {
            var profiles = new JsonArray();
            var seen = new ArrayList<String>();
            for (var location : list()) {
                var entry = location.json();
                boolean shadowed = seen.contains(location.name());
                entry.addProperty("shadowed", shadowed);
                profiles.add(entry);
                seen.add(location.name());
                print(location.name() + "\t" + location.scope()
                        + (shadowed ? " (shadowed)" : "") + "\t"
                        + location.directory().toAbsolutePath().normalize());
            }
            if (profiles.isEmpty()) {
                print(includePresets ? "No profiles."
                        : "No profiles. Run jira init or jira profile init NAME.");
            }
            var result = new JsonObject();
            result.add("profiles", profiles);
            if (includePresets) {
                var presets = new JsonArray();
                for (var preset : PRESETS) {
                    presets.add(preset);
                    print("Preset\t" + preset);
                }
                result.add("presets", presets);
            }
            machineData = result;
        }
    }

    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "Create a new profile from a bundled preset or another profile.")
    static final class Init extends JiraCommand.Action {
        @Parameters(index = "0", paramLabel = "NAME", description = "New profile name.")
        String profile;
        @Option(names = "--from", paramLabel = "NAME",
                description = "Copy another profile; missing files use the standard preset.")
        String from;
        @Option(names = "--preset", paramLabel = "NAME",
                description = "Seed from a built-in preset (standard, story, bug, spike, agile; "
                        + "default: standard).")
        String preset;
        @Option(names = {"-g", "--global"},
                description = "Create under ~/.jira/profiles instead of .jira/profiles.")
        boolean global;
        @Option(names = "--language", defaultValue = "en", paramLabel = "en|zh",
                description = "Language of bundled templates: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
        JiraSkillCommands.Language language;

        @Override
        protected void execute() throws IOException {
            if (from != null && preset != null) {
                throw wrong("Use either --from or --preset, not both.");
            }
            var selectedPreset = preset(preset == null ? STANDARD : preset);
            var target = root(global).resolve(name(profile));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw wrong("Profile already exists: " + target.toAbsolutePath().normalize()
                        + "; existing files are never overwritten.");
            }
            Path source = null;
            if (from != null) {
                var found = find(from);
                if (found.isEmpty()) {
                    throw missing(from);
                }
                source = found.get().directory();
            }
            var created = materialize(target, source, selectedPreset, language);
            var result = new Location(profile, global ? "global" : "local", target).json();
            if (from != null) {
                result.addProperty("from", from);
            } else {
                result.addProperty("preset", selectedPreset);
            }
            result.add("createdFiles", paths(created));
            machineData = result;
            print("Created profile " + profile + " at " + target.toAbsolutePath().normalize() + ".");
        }
    }
}
