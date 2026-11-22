package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards log selection: filtering happens before the tail limit, and truncation is reported. */
class LogTailTest {
    @TempDir Path directory;

    private Path log(String... lines) throws IOException {
        var file = directory.resolve("task.log");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    @Test
    void returnsEveryLineWhenUnlimited() throws IOException {
        var file = log("first", "second", "third");

        var result = LogTail.read(file, 0, null);

        assertThat(result.lines()).containsExactly("first", "second", "third");
        assertThat(result.matched()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void keepsOnlyTheLastLines() throws IOException {
        var file = log("a", "b", "c", "d", "e");

        var result = LogTail.read(file, 2, null);

        assertThat(result.lines()).containsExactly("d", "e");
        assertThat(result.matched()).isEqualTo(5);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void filtersBeforeApplyingTheLimit() throws IOException {
        var file = log("ok", "ERROR one", "ok", "ERROR two", "ERROR three");

        var result = LogTail.read(file, 2, Pattern.compile("ERROR"));

        assertThat(result.lines()).containsExactly("ERROR two", "ERROR three");
        assertThat(result.matched()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void reportsNoTruncationWhenEverythingFits() throws IOException {
        var file = log("only", "two");

        var result = LogTail.read(file, 5, null);

        assertThat(result.lines()).containsExactly("only", "two");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void filtersWithoutALimit() throws IOException {
        var file = log("a", "needle", "b", "needle again");

        var result = LogTail.read(file, 0, Pattern.compile("needle"));

        assertThat(result.lines()).containsExactly("needle", "needle again");
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.truncated()).isFalse();
    }
}
