package io.github.devpulse.jira.cli;

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Access to the golden files under {@code src/test/resources/golden}.
 *
 * <p>Verification is the default mode. Pass {@code -Dgolden.update=true} to overwrite every golden
 * file with the freshly rendered output instead of comparing; this is meant for intentional
 * rendering changes and the written files must be reviewed before being kept.
 *
 * <p>Golden files are UTF-8 and use LF line endings. Trailing whitespace at the end of the file is
 * not significant, matching the {@code stripTrailing()} contract of the assertions these tests
 * replaced.
 */
final class GoldenFiles {

    private static final Path SOURCE_DIRECTORY = Path.of("src", "test", "resources", "golden");
    private static final String CLASSPATH_DIRECTORY = "/golden/";
    private static final String UPDATE_PROPERTY = "golden.update";
    private static final String UPDATE_FLAG = "-Dgolden.update=true";
    private static final int CONTEXT_LINES = 3;

    private GoldenFiles() {}

    static boolean updating() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    /** Reads a UTF-8 golden file, normalizing its line endings to LF. */
    static String read(String name) {
        var source = SOURCE_DIRECTORY.resolve(name);
        if (Files.isRegularFile(source)) {
            try {
                return normalize(Files.readString(source, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to read golden file: " + source, exception);
            }
        }
        try (InputStream stream =
                GoldenFiles.class.getResourceAsStream(CLASSPATH_DIRECTORY + name)) {
            if (stream == null) {
                throw new IllegalArgumentException(
                        "Missing golden file: " + CLASSPATH_DIRECTORY + name);
            }
            return normalize(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to read golden file: " + CLASSPATH_DIRECTORY + name, exception);
        }
    }

    /**
     * Compares {@code actual} with a golden file. On mismatch the failure message names the case
     * and artifact, reports the first differing line and character, and shows a small context
     * window instead of dumping both documents.
     */
    static void assertMatches(String caseName, String artifact, String goldenName, String actual) {
        var actualText = actual.stripTrailing();
        if (updating()) {
            write(goldenName, actualText);
            return;
        }
        var expectedText = read(goldenName).stripTrailing();
        if (expectedText.equals(actualText)) {
            return;
        }
        fail(describeMismatch(caseName, artifact, goldenName, expectedText, actualText));
    }

    private static void write(String name, String content) {
        var target = SOURCE_DIRECTORY.resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(
                    target, normalize(content).stripTrailing() + "\n", StandardCharsets.UTF_8);
            System.out.println("Updated golden file: " + target);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to update golden file: " + target, exception);
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String describeMismatch(
            String caseName, String artifact, String goldenName, String expected, String actual) {
        var expectedLines = expected.split("\n", -1);
        var actualLines = actual.split("\n", -1);
        var difference = firstDifference(expectedLines, actualLines);
        var report = new StringBuilder();
        report.append("Golden file mismatch\n")
                .append("  case     : ")
                .append(caseName)
                .append('\n')
                .append("  artifact : ")
                .append(artifact)
                .append('\n')
                .append("  golden   : ")
                .append(CLASSPATH_DIRECTORY)
                .append(goldenName)
                .append('\n')
                .append("  location : line ")
                .append(difference.line() + 1);
        if (difference.column() >= 0) {
            report.append(", character ").append(difference.column() + 1);
        }
        report.append('\n')
                .append("  lines    : expected ")
                .append(expectedLines.length)
                .append(", actual ")
                .append(actualLines.length)
                .append("\n\n");

        if (difference.column() >= 0) {
            report.append("  expected : \"")
                    .append(quoted(expectedLines[difference.line()]))
                    .append("\"\n")
                    .append("  actual   : \"")
                    .append(quoted(actualLines[difference.line()]))
                    .append("\"\n\n");
        }

        var contextStart = Math.max(0, difference.line() - CONTEXT_LINES);
        var contextEnd =
                Math.min(
                        Math.max(expectedLines.length, actualLines.length),
                        difference.line() + CONTEXT_LINES + 1);
        report.append("  context:\n");
        for (var line = contextStart; line < contextEnd; line++) {
            var marker = line == difference.line() ? '>' : ' ';
            report.append(marker)
                    .append(
                            String.format(
                                    " %4d expected | %s\n", line + 1, lineAt(expectedLines, line)))
                    .append(marker)
                    .append(
                            String.format(
                                    " %4d actual   | %s\n", line + 1, lineAt(actualLines, line)));
        }
        report.append('\n').append("  Regenerate with: ").append(UPDATE_FLAG).append('\n');
        return report.toString();
    }

    private static Difference firstDifference(String[] expected, String[] actual) {
        var commonLines = Math.min(expected.length, actual.length);
        for (var line = 0; line < commonLines; line++) {
            if (!expected[line].equals(actual[line])) {
                return new Difference(line, firstDifferentCharacter(expected[line], actual[line]));
            }
        }
        return new Difference(commonLines, -1);
    }

    private static int firstDifferentCharacter(String expected, String actual) {
        var commonCharacters = Math.min(expected.length(), actual.length());
        for (var index = 0; index < commonCharacters; index++) {
            if (expected.charAt(index) != actual.charAt(index)) {
                return index;
            }
        }
        return commonCharacters;
    }

    private static String lineAt(String[] lines, int line) {
        return line < lines.length ? lines[line] : "<missing line>";
    }

    private static String quoted(String line) {
        return line.replace("\t", "\\t").replace("\r", "\\r");
    }

    private record Difference(int line, int column) {}
}
