package io.github.devpulse.execution;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record TaskDefinition(String name, String command, List<String> needs,
        Daemon daemon) {
    public TaskDefinition {
        Objects.requireNonNull(name, "name");
        command = Objects.requireNonNull(command, "command").replace("\r\n", "\n");
        needs = List.copyOf(new LinkedHashSet<>(needs));
    }

    public TaskDefinition(String name, String command, List<String> needs) {
        this(name, command, needs, null);
    }

    public record Daemon(Readiness ready, boolean shutdownHook,
            String stopCommand, Duration stopTimeout) {
        public Daemon {
            Objects.requireNonNull(ready, "ready");
            Objects.requireNonNull(stopTimeout, "stopTimeout");
        }
    }

    public record Readiness(String command, Duration interval,
            Duration timeout, Duration checkTimeout) {
        public Readiness {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(checkTimeout, "checkTimeout");
        }
    }
}
