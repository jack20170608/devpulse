package io.github.devpulse.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * What a client needs to reach a running server, written when the server starts.
 *
 * <p>The JSON is written and read field by field rather than through a reflective mapper: Gson's
 * record support needs reflection at runtime, which the native image does not provide without extra
 * registration.
 */
public record ServerState(long pid, int port, String bootId, String version, Instant startedAt) {

    public void write(Path file) throws IOException {
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var json =
                "{\"pid\":"
                        + pid
                        + ",\"port\":"
                        + port
                        + ",\"bootId\":"
                        + quote(bootId)
                        + ",\"version\":"
                        + quote(version)
                        + ",\"startedAt\":"
                        + quote(startedAt.toString())
                        + "}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /** The recorded state, or empty when no server ever wrote one. */
    public static Optional<ServerState> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            var root =
                    JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            return Optional.of(
                    new ServerState(
                            root.get("pid").getAsLong(),
                            root.get("port").getAsInt(),
                            root.get("bootId").getAsString(),
                            root.get("version").getAsString(),
                            Instant.parse(root.get("startedAt").getAsString())));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    private static String quote(String value) {
        var escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
