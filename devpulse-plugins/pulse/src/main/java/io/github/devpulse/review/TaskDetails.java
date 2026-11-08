package io.github.devpulse.review;

import io.github.devpulse.core.bash.BashTaskDefinition;
import io.github.devpulse.core.dag.DaemonOptions;
import java.util.List;

public final class TaskDetails {
    private TaskDetails() {}

    public static List<String> daemonDetails(BashTaskDefinition task, DaemonOptions options) {
        var daemon = task.daemon();
        if (daemon == null) {
            return List.of();
        }
        return List.of("Type: daemon; needs waits for readiness",
                "Readiness / Bash: " + daemon.readyCommand(),
                "Readiness timeout: " + options.readyTimeout()
                        + "; interval: " + options.checkInterval()
                        + "; check timeout: " + options.checkTimeout(),
                "Lifecycle: user-managed; no automatic stop",
                "Stop / Bash: " + (daemon.stopCommand() == null
                        ? "(terminate owned process tree)" : daemon.stopCommand()),
                "Stop timeout: " + daemon.stopTimeout());
    }
}
