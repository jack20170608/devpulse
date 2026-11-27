package io.github.devpulse.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/**
 * The bearer token that protects the loopback API.
 *
 * <p>The token is per user, not per server run: restarting the server must not invalidate every
 * client. It is created on first use with owner-only permissions; a platform without POSIX
 * permissions keeps the file readable by the user who created it, which is the best it can do.
 */
public final class ServerToken {
    private static final int BYTES = 32;

    private ServerToken() {}

    /** Read the token, creating one when the file does not exist yet. */
    public static String loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            return read(file);
        }
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var bytes = new byte[BYTES];
        new SecureRandom().nextBytes(bytes);
        var token = HexFormat.of().formatHex(bytes);
        Files.writeString(
                file,
                token,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        restrict(file);
        return token;
    }

    private static String read(Path file) throws IOException {
        var token = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) {
            throw new IOException("Server token file is empty: " + file);
        }
        return token;
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and other non-POSIX filesystems: the file is still only in the user's home.
        }
    }
}
