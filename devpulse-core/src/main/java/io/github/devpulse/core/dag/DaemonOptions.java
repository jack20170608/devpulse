package io.github.devpulse.core.dag;

import java.time.Duration;
import java.util.Objects;

public record DaemonOptions(Duration readyTimeout, Duration checkInterval,
        Duration checkTimeout) {
    public static final DaemonOptions DEFAULT = new DaemonOptions(
            Duration.ofSeconds(30), Duration.ofMillis(500), Duration.ofSeconds(2));

    public DaemonOptions {
        validate("readyTimeout", readyTimeout);
        validate("checkInterval", checkInterval);
        validate("checkTimeout", checkTimeout);
    }

    private static void validate(String name, Duration value) {
        Objects.requireNonNull(value, name);
        try {
            if (value.toNanos() <= 0) {
                throw new IllegalArgumentException(name + " must be positive.");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large.", exception);
        }
    }
}
