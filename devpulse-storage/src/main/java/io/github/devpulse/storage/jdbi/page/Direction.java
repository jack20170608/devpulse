package io.github.devpulse.storage.jdbi.page;

import java.util.Objects;

public enum Direction {
    ASC,
    DESC;

    public static Direction fromString(String value) {
        if (Objects.isNull(value)) {
            return null;
        }
        try {
            return Direction.valueOf(value.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format(
                            "Invalid value '%s' for orders given! Has to be either 'desc' or 'asc' (case insensitive).",
                            value),
                    e);
        }
    }
}
