package io.github.devpulse.core.dag;

import java.time.Duration;
import java.util.Objects;

public record TaskOptions(int maxAttempts, Duration retryDelay, Duration timeout) {
    public static final TaskOptions DEFAULT = new TaskOptions(1, Duration.ZERO, null);

    public TaskOptions {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive.");
        }
        Objects.requireNonNull(retryDelay, "retryDelay");
        validateDuration("retryDelay", retryDelay, true);
        if (timeout != null) {
            validateDuration("timeout", timeout, false);
        }
    }

    private static void validateDuration(String name, Duration value, boolean allowZero) {
        try {
            if (value.toNanos() < 0 || (!allowZero && value.isZero())) {
                throw new IllegalArgumentException(name + " must be "
                        + (allowZero ? "nonnegative." : "positive."));
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large.", exception);
        }
    }
}
