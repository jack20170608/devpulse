package io.github.devpulse.core.bash;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record BashTaskDefinition(String name, String command, List<String> needs,
        Daemon daemon) {
    public BashTaskDefinition {
        Objects.requireNonNull(name, "name");
        command = Objects.requireNonNull(command, "command").replace("\r\n", "\n");
        needs = List.copyOf(new LinkedHashSet<>(needs));
    }

    public BashTaskDefinition(String name, String command, List<String> needs) {
        this(name, command, needs, null);
    }

    public record Daemon(String readyCommand, String stopCommand, Duration stopTimeout) {
        public Daemon {
            Objects.requireNonNull(readyCommand, "readyCommand");
            Objects.requireNonNull(stopTimeout, "stopTimeout");
        }
    }
}
