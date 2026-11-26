package io.github.devpulse.storage;

import java.time.Instant;

/**
 * One project the central store knows about.
 *
 * <p>The path is the identity anchor: it comes from {@code toRealPath()}, so a symlink or a Windows
 * case variant cannot create a second project for the same directory.
 */
public record ProjectRecord(
        String id, String path, String name, Instant createdAt, Instant lastSeenAt) {}
