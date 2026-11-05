package io.github.devpulse.core.execution;

import io.github.devpulse.core.execution.PlanningException.Scope;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public record TaskPlan(List<TaskDefinition> tasks) {
    public TaskPlan {
        tasks = List.copyOf(tasks);
    }

    public static TaskPlan create(WorkflowDefinition workflow, String target) {
        if (workflow.tasks().isEmpty()) {
            throw new PlanningException(Scope.WORKFLOW, null, "tasks",
                    "At least one task is required.");
        }
        var tasks = new LinkedHashMap<String, TaskDefinition>();
        for (var task : workflow.tasks()) {
            validateTask(task);
            if (tasks.putIfAbsent(task.name(), task) != null) {
                throw invalidTask(task, "name", "Duplicate task: " + task.name());
            }
        }
        for (var task : tasks.values()) {
            for (var dependency : task.needs()) {
                if (!tasks.containsKey(dependency)) {
                    throw invalidTask(task, "needs", "Task " + task.name()
                            + " has unknown dependency: " + dependency);
                }
            }
        }
        for (var entry : workflow.pipelines().entrySet()) {
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
        var indegree = new HashMap<String, Integer>();
        var dependents = new HashMap<String, List<String>>();
        for (var task : tasks.values()) {
            indegree.put(task.name(), task.needs().size());
            for (var dependency : task.needs()) {
                dependents.computeIfAbsent(dependency,
                        ignored -> new ArrayList<>()).add(task.name());
            }
        }
        var ready = new ArrayDeque<String>();
        tasks.keySet().stream().filter(name -> indegree.get(name) == 0)
                .forEach(ready::addLast);
        var ordered = new ArrayList<TaskDefinition>();
        while (!ready.isEmpty()) {
            var name = ready.removeFirst();
            ordered.add(tasks.get(name));
            for (var next : dependents.getOrDefault(name, List.of())) {
                if (indegree.compute(next, (key, value) -> value - 1) == 0) {
                    ready.addLast(next);
                }
            }
        }
        if (ordered.size() != tasks.size()) {
            var unresolved = tasks.keySet().stream()
                    .filter(name -> indegree.get(name) > 0).toList();
            throw invalidTask(tasks.get(unresolved.getFirst()), "needs",
                    "Dependency cycle detected; unresolved tasks: "
                            + String.join(", ", unresolved));
        }
        if (tasks.containsKey(target)
                && workflow.pipelines().containsKey(target)) {
            throw new PlanningException(Scope.TARGET, target, "target",
                    "Ambiguous target: " + target
                    + " is both a task and a pipeline. Rename one.");
        }
        List<String> roots;
        if (workflow.pipelines().containsKey(target)) {
            roots = workflow.pipelines().get(target);
        } else if (tasks.containsKey(target)) {
            roots = List.of(target);
        } else {
            throw new PlanningException(Scope.TARGET, target, "target",
                    "Unknown task or pipeline: " + target);
        }
        var selected = new LinkedHashSet<String>();
        var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            var name = pending.removeFirst();
            if (selected.add(name)) {
                pending.addAll(tasks.get(name).needs());
            }
        }
        return new TaskPlan(ordered.stream()
                .filter(task -> selected.contains(task.name())).toList());
    }

    private static void validateTask(TaskDefinition task) {
        if (task.name().isBlank() || !task.name().equals(task.name().strip())
                || task.name().chars().anyMatch(Character::isISOControl)) {
            throw invalidTask(task, "name",
                    "Task names must be nonblank without surrounding whitespace "
                            + "or control characters.");
        }
        if (task.command().isBlank()) {
            throw invalidTask(task, "cmd", "Must not be blank.");
        }
        var daemon = task.daemon();
        if (daemon != null) {
            if (daemon.ready().command().isBlank()) {
                throw invalidTask(task, "ready.cmd", "Must not be blank.");
            }
            validateDuration(task, "ready.interval", daemon.ready().interval());
            validateDuration(task, "ready.timeout", daemon.ready().timeout());
            validateDuration(task, "ready.checkTimeout", daemon.ready().checkTimeout());
            validateDuration(task, "stopTimeout", daemon.stopTimeout());
            if (daemon.stopCommand() != null && daemon.stopCommand().isBlank()) {
                throw invalidTask(task, "stop", "Must not be blank.");
            }
        }
    }

    private static void validateDuration(TaskDefinition task, String field,
            Duration value) {
        try {
            if (value.toNanos() <= 0) {
                throw invalidTask(task, field, "Must be a positive duration.");
            }
        } catch (ArithmeticException exception) {
            throw invalidTask(task, field, "Duration is too large.");
        }
    }

    private static PlanningException invalidTask(TaskDefinition task,
            String field, String message) {
        return new PlanningException(Scope.TASK, task.name(), field, message);
    }
}
