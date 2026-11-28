package io.github.devpulse.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the {@code [WORKFLOW]} argument into a file.
 *
 * <p>An argument with a path separator or a {@code .yaml}/{@code .yml} suffix is a path; anything
 * else is an id looked up under the workflow directory. A miss reports the candidates, because "not
 * found" without the alternatives is a dead end.
 */
final class WorkflowFiles {
    private WorkflowFiles() {}

    /** The workflow directory, resolved against the project directory. */
    static Path directory(Path project, Path configured) {
        return configured.isAbsolute() ? configured : project.resolve(configured);
    }

    static Path resolve(Path project, Path configured, String argument) throws IOException {
        if (argument == null || argument.isBlank()) {
            throw new IOException(
                    "No workflow given. Available: "
                            + String.join(", ", available(project, configured)));
        }
        var lower = argument.toLowerCase(Locale.ROOT);
        var looksLikePath =
                argument.indexOf('/') >= 0
                        || argument.indexOf('\\') >= 0
                        || lower.endsWith(".yaml")
                        || lower.endsWith(".yml");
        var file =
                looksLikePath
                        ? project.resolve(argument).normalize()
                        : directory(project, configured).resolve(argument + ".yaml").normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException(
                    "Workflow not found: "
                            + file
                            + ". Available: "
                            + String.join(", ", available(project, configured)));
        }
        return file;
    }

    static List<String> available(Path project, Path configured) throws IOException {
        var directory = directory(project, configured);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var ids = new ArrayList<String>();
        try (var entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.lastIndexOf('.')))
                    .sorted()
                    .forEach(ids::add);
        }
        return List.copyOf(ids);
    }
}
