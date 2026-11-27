package io.github.devpulse.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Selects the part of a log a caller actually needs.
 *
 * <p>Reading is streamed and bounded: only the requested tail is retained, while the total and
 * matching line counts are computed during the same pass. Logs are the largest thing this CLI can
 * put into a caller's context, so nothing here reads the whole file into memory.
 */
final class LogTail {
    /**
     * @param lines the selected lines, in order
     * @param matched lines that matched the filter, before the tail limit
     * @param total every line read from the file
     * @param truncated whether the tail limit dropped matching lines
     */
    record Result(List<String> lines, long matched, long total, boolean truncated) {}

    private LogTail() {}

    /**
     * @param limit maximum retained lines, or {@code 0} for no limit
     * @param filter line filter, or {@code null} to keep every line
     */
    static Result read(Path file, int limit, Pattern filter) throws IOException {
        var unlimited = limit <= 0;
        var tail = unlimited ? null : new ArrayDeque<String>(Math.min(limit, 1024));
        var kept = unlimited ? new ArrayList<String>() : null;
        long total = 0;
        long matched = 0;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                if (filter != null && !filter.matcher(line).find()) {
                    continue;
                }
                matched++;
                if (unlimited) {
                    kept.add(line);
                } else {
                    if (tail.size() == limit) {
                        tail.removeFirst();
                    }
                    tail.addLast(line);
                }
            }
        }
        if (unlimited) {
            return new Result(List.copyOf(kept), matched, total, false);
        }
        var lines = List.copyOf(tail);
        return new Result(lines, matched, total, matched > lines.size());
    }
}
