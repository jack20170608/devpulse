package io.github.devpulse.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content addressing for workflow payloads. */
public final class ContentHash {
    private ContentHash() {}

    /** The SHA-256 of the UTF-8 bytes, as lowercase hex. */
    public static String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
