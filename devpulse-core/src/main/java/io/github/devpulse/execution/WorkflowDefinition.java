package io.github.devpulse.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkflowDefinition(List<TaskDefinition> tasks,
        Map<String, List<String>> pipelines) {
    public WorkflowDefinition {
        tasks = List.copyOf(tasks);
        var copy = new LinkedHashMap<String, List<String>>();
        pipelines.forEach((name, targets) -> copy.put(
                Objects.requireNonNull(name, "pipeline name"), List.copyOf(targets)));
        pipelines = Collections.unmodifiableMap(copy);
    }
}
