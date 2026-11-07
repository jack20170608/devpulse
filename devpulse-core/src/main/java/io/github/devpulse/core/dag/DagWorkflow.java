package io.github.devpulse.core.dag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DagWorkflow {
    record Node<T>(TaskKey<T> key, List<TaskKey<?>> needs, DagTask<T> task,
            TaskOptions options) {}

    private final Map<String, Node<?>> nodes;
    private final List<TaskKey<?>> topologicalOrder;

    private DagWorkflow(Map<String, Node<?>> definitions) {
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("A workflow must contain at least one task.");
        }
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        var remaining = new LinkedHashMap<String, Integer>();
        var dependents = new LinkedHashMap<String, List<String>>();
        for (var node : nodes.values()) {
            remaining.put(node.key().id(), node.needs().size());
            for (var dependency : node.needs()) {
                var prerequisite = nodes.get(dependency.id());
                if (prerequisite == null || !prerequisite.key().equals(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency or result type mismatch: "
                            + node.key().id() + " -> " + dependency);
                }
                dependents.computeIfAbsent(dependency.id(), ignored -> new ArrayList<>())
                        .add(node.key().id());
            }
        }
        var ready = new ArrayDeque<String>();
        remaining.forEach((id, count) -> {
            if (count == 0) {
                ready.addLast(id);
            }
        });
        var order = new ArrayList<TaskKey<?>>();
        while (!ready.isEmpty()) {
            var id = ready.removeFirst();
            order.add(nodes.get(id).key());
            for (var next : dependents.getOrDefault(id, List.of())) {
                if (remaining.compute(next, (key, value) -> value - 1) == 0) {
                    ready.addLast(next);
                }
            }
        }
        if (order.size() != nodes.size()) {
            var unresolved = remaining.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0).map(Map.Entry::getKey).toList();
            throw new IllegalArgumentException("Dependency cycle; unresolved tasks: " + unresolved);
        }
        topologicalOrder = List.copyOf(order);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<TaskKey<?>> topologicalOrder() {
        return topologicalOrder;
    }

    public DagRun newRun(int parallelism) {
        return newRun(parallelism, event -> {});
    }

    public DagRun newRun(int parallelism, DagRun.Listener listener) {
        return new DagRun(this, parallelism, listener);
    }

    Map<String, Node<?>> nodes() {
        return nodes;
    }

    public static final class Builder {
        private final Map<String, Node<?>> nodes = new LinkedHashMap<>();

        private Builder() {}

        public <T> Builder task(TaskKey<T> key, DagTask<T> task) {
            return task(key, List.of(), task, TaskOptions.DEFAULT);
        }

        public <T> Builder task(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                DagTask<T> task) {
            return task(key, needs, task, TaskOptions.DEFAULT);
        }

        public <T> Builder task(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                DagTask<T> task, TaskOptions options) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(options, "options");
            List<TaskKey<?>> dependencies = List.copyOf(needs);
            if (new LinkedHashSet<>(dependencies).size() != dependencies.size()) {
                throw new IllegalArgumentException("Duplicate dependencies for " + key.id());
            }
            if (dependencies.stream().anyMatch(dependency -> dependency.id().equals(key.id()))) {
                throw new IllegalArgumentException("Task cannot depend on itself: " + key.id());
            }
            if (nodes.putIfAbsent(key.id(), new Node<>(key, dependencies, task, options)) != null) {
                throw new IllegalArgumentException("Duplicate task ID: " + key.id());
            }
            return this;
        }

        public DagWorkflow build() {
            return new DagWorkflow(nodes);
        }
    }
}
