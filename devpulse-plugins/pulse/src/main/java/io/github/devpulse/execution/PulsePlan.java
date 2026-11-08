package io.github.devpulse.execution;

import io.github.devpulse.core.dag.DagGraph;
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.DaemonOptions;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.bash.BashTaskAdapter;
import io.github.devpulse.core.bash.BashTaskDefinition;
import io.github.devpulse.execution.PlanningException.Scope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PulsePlan(DagGraph graph, List<BashTaskDefinition> tasks,
        Map<String, DaemonOptions> daemonOptions) {
    public PulsePlan {
        tasks = List.copyOf(tasks);
        daemonOptions = Map.copyOf(daemonOptions);
    }

    static PulsePlan create(List<BashTaskDefinition> definitions,
            Map<String, List<String>> pipelines, Map<String, DaemonOptions> daemonOptions,
            String target) {
        if (definitions.isEmpty()) {
            throw new PlanningException(Scope.WORKFLOW, null, "tasks",
                    "At least one task is required.");
        }
        var tasks = new LinkedHashMap<String, BashTaskDefinition>();
        var keys = new LinkedHashMap<String, TaskKey<?>>();
        var planning = DagGraph.builder();
        for (var task : definitions) {
            validateTask(task);
            tasks.put(task.name(), task);
            keys.put(task.name(), key(task));
        }
        DagGraph graph;
        try {
            for (var task : definitions) {
                var needs = task.needs().stream().map(name -> {
                    var known = keys.get(name);
                    if (known != null) {
                        return known;
                    }
                    try {
                        return TaskKey.of(name, Void.class);
                    } catch (IllegalArgumentException exception) {
                        throw invalidTask(task, "needs", "Unknown dependency: " + name);
                    }
                }).toList();
                planning.node(keys.get(task.name()), needs);
            }
            graph = planning.build();
        } catch (DagGraph.InvalidGraph exception) {
            throw invalidTask(tasks.get(exception.task().id()), "needs", exception.getMessage());
        }
        for (var entry : pipelines.entrySet()) {
            if (entry.getValue().isEmpty()) {
                throw new PlanningException(Scope.PIPELINE, entry.getKey(), "tasks",
                        "Pipeline " + entry.getKey() + " must contain at least one task.");
            }
            for (var name : entry.getValue()) {
                if (!tasks.containsKey(name)) {
                    throw new PlanningException(Scope.PIPELINE, entry.getKey(), "tasks",
                            "Pipeline " + entry.getKey() + " has unknown task: " + name);
                }
            }
        }
        if (tasks.containsKey(target)
                && pipelines.containsKey(target)) {
            throw new PlanningException(Scope.TARGET, target, "target",
                    "Ambiguous target: " + target
                    + " is both a task and a pipeline. Rename one.");
        }
        List<String> roots;
        if (pipelines.containsKey(target)) {
            roots = pipelines.get(target);
        } else if (tasks.containsKey(target)) {
            roots = List.of(target);
        } else {
            throw new PlanningException(Scope.TARGET, target, "target",
                    "Unknown task or pipeline: " + target);
        }
        var selected = graph.select(roots.stream().map(keys::get).toList());
        return new PulsePlan(selected, selected.topologicalOrder().stream()
                .map(key -> tasks.get(key.id())).toList(), daemonOptions);
    }

    public DagWorkflow workflow(BashTaskAdapter adapter) {
        var workflow = DagWorkflow.builder(graph);
        for (var task : tasks) {
            if (task.daemon() == null) {
                workflow.task(TaskKey.of(task.name(), Integer.class), adapter.task(task.command()));
            } else {
                workflow.daemon(TaskKey.of(task.name(), ProcessHandle.class),
                        adapter.daemon(task.command(), task.daemon()),
                        daemonOptions.get(task.name()));
            }
        }
        return workflow.build();
    }

    private static TaskKey<?> key(BashTaskDefinition task) {
        try {
            return task.daemon() == null ? TaskKey.of(task.name(), Integer.class)
                    : TaskKey.of(task.name(), ProcessHandle.class);
        } catch (IllegalArgumentException exception) {
            throw invalidTask(task, "name", exception.getMessage());
        }
    }

    private static void validateTask(BashTaskDefinition task) {
        if (task.command().isBlank()) {
            throw invalidTask(task, "cmd", "Must not be blank.");
        }
        var daemon = task.daemon();
        if (daemon != null) {
            if (daemon.readyCommand().isBlank()) {
                throw invalidTask(task, "ready.cmd", "Must not be blank.");
            }
            validateDuration(task, "stopTimeout", daemon.stopTimeout());
            if (daemon.stopCommand() != null && daemon.stopCommand().isBlank()) {
                throw invalidTask(task, "stop", "Must not be blank.");
            }
        }
    }

    private static void validateDuration(BashTaskDefinition task, String field,
            Duration value) {
        try {
            if (value.toNanos() <= 0) {
                throw invalidTask(task, field, "Must be a positive duration.");
            }
        } catch (ArithmeticException exception) {
            throw invalidTask(task, field, "Duration is too large.");
        }
    }

    private static PlanningException invalidTask(BashTaskDefinition task,
            String field, String message) {
        return new PlanningException(Scope.TASK, task.name(), field, message);
    }
}
