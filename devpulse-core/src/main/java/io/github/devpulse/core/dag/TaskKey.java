package io.github.devpulse.core.dag;

import java.util.Objects;

public record TaskKey<T>(String id, Class<T> resultType) {
    public TaskKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resultType, "resultType");
        if (id.isBlank() || !id.equals(id.strip())
                || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Task IDs must be nonblank without surrounding whitespace or control characters.");
        }
        if (resultType.isPrimitive()) {
            throw new IllegalArgumentException("Use a boxed result type, or Void.class.");
        }
    }

    public static <T> TaskKey<T> of(String id, Class<T> resultType) {
        return new TaskKey<>(id, resultType);
    }
}
