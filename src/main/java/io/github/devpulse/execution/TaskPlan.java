package io.github.devpulse.execution;

import com.typesafe.config.ConfigException;
import io.github.devpulse.execution.PulseConfiguration.Task;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

public record TaskPlan(List<Task> tasks) {
    public TaskPlan {
        tasks = List.copyOf(tasks);
    }

    public static TaskPlan create(PulseConfiguration configuration,
            String target) throws IOException {
        var tasks = configuration.tasks();
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
        var ordered = new ArrayList<Task>();
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
            throw new ConfigException.BadValue(
                    tasks.get(unresolved.getFirst()).origin(), "needs",
                    "Dependency cycle detected; unresolved tasks: "
                            + String.join(", ", unresolved));
        }
        if (tasks.containsKey(target)
                && configuration.pipelines().containsKey(target)) {
            throw new IOException("Ambiguous target: " + target
                    + " is both a task and a pipeline. Rename one.");
        }
        List<String> roots;
        if (configuration.pipelines().containsKey(target)) {
            roots = configuration.pipelines().get(target).tasks();
        } else if (tasks.containsKey(target)) {
            roots = List.of(target);
        } else {
            throw new IOException("Unknown task or pipeline: " + target);
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
}
