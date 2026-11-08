package io.github.devpulse.core.dag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DagGraph {
    public record Node(TaskKey<?> key, List<TaskKey<?>> needs) {
        public Node {
            Objects.requireNonNull(key, "key");
            needs = List.copyOf(needs);
        }
    }

    public static final class InvalidGraph extends IllegalArgumentException {
        private final TaskKey<?> task;

        private InvalidGraph(TaskKey<?> task, String message) {
            super(message);
            this.task = task;
        }

        public TaskKey<?> task() {
            return task;
        }
    }

    private final Map<String, Node> nodes;
    private final List<TaskKey<?>> topologicalOrder;

    private DagGraph(Map<String, Node> definitions) {
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
                    throw new InvalidGraph(node.key(), "Unknown dependency or result type mismatch: "
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
            throw new InvalidGraph(nodes.get(unresolved.getFirst()).key(),
                    "Dependency cycle; unresolved tasks: " + unresolved);
        }
        topologicalOrder = List.copyOf(order);
    }

    private DagGraph(Map<String, Node> nodes, List<TaskKey<?>> order) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        topologicalOrder = List.copyOf(order);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Node node(TaskKey<?> key) {
        Objects.requireNonNull(key, "key");
        var node = nodes.get(key.id());
        if (node == null || !node.key().equals(key)) {
            throw new IllegalArgumentException("Unknown task or result type mismatch: " + key);
        }
        return node;
    }

    public List<TaskKey<?>> topologicalOrder() {
        return topologicalOrder;
    }

    public DagGraph select(List<? extends TaskKey<?>> roots) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("Select at least one task.");
        }
        roots.forEach(this::node);
        var selected = new LinkedHashSet<TaskKey<?>>();
        var pending = new ArrayDeque<TaskKey<?>>(roots);
        while (!pending.isEmpty()) {
            var key = pending.removeFirst();
            if (selected.add(key)) {
                pending.addAll(node(key).needs());
            }
        }
        var order = topologicalOrder.stream().filter(selected::contains).toList();
        var subset = new LinkedHashMap<String, Node>();
        order.forEach(key -> subset.put(key.id(), node(key)));
        return new DagGraph(subset, order);
    }

    public static final class Builder {
        private final Map<String, Node> nodes = new LinkedHashMap<>();

        private Builder() {}

        public Builder node(TaskKey<?> key, List<? extends TaskKey<?>> needs) {
            Objects.requireNonNull(key, "key");
            List<TaskKey<?>> dependencies = List.copyOf(needs);
            if (new LinkedHashSet<>(dependencies).size() != dependencies.size()) {
                throw new InvalidGraph(key, "Duplicate dependencies for " + key.id());
            }
            if (dependencies.stream().anyMatch(dependency -> dependency.id().equals(key.id()))) {
                throw new InvalidGraph(key, "Task cannot depend on itself: " + key.id());
            }
            if (nodes.putIfAbsent(key.id(), new Node(key, dependencies)) != null) {
                throw new InvalidGraph(key, "Duplicate task ID: " + key.id());
            }
            return this;
        }

        public DagGraph build() {
            return new DagGraph(nodes);
        }
    }
}
