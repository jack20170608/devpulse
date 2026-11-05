package io.github.devpulse.review;

import io.github.devpulse.execution.TaskDefinition;
import java.util.List;

public final class TaskDetails {
    private TaskDetails() {}

    public static List<String> daemonDetails(TaskDefinition task) {
        var daemon = task.daemon();
        if (daemon == null) {
            return List.of();
        }
        return List.of("Type: daemon; needs waits for readiness",
                "Readiness / Bash: " + daemon.ready().command(),
                "Readiness timeout: " + daemon.ready().timeout()
                        + "; interval: " + daemon.ready().interval()
                        + "; check timeout: " + daemon.ready().checkTimeout(),
                "shutdownHook: " + daemon.shutdownHook(),
                "Stop / Bash: " + (daemon.stopCommand() == null
                        ? "(terminate owned process tree)" : daemon.stopCommand()),
                "Stop timeout: " + daemon.stopTimeout());
    }
}
