package io.github.devpulse.core.dag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DagWorkflow {
    sealed interface Action<T> permits Batch, Daemon {}
    record Batch<T>(DagTask<T> task) implements Action<T> {}
    record Daemon<T>(DagDaemon<T> task, DaemonOptions options) implements Action<T> {}
    record Node<T>(TaskKey<T> key, Action<T> action, TaskOptions options) {}

    private final Map<String, Node<?>> nodes;
    private final DagGraph graph;

    private DagWorkflow(Map<String, Node<?>> definitions, DagGraph graph) {
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.graph = graph;
    }

    public static Builder builder() {
        return new Builder(null);
    }

    public static Builder builder(DagGraph graph) {
        return new Builder(Objects.requireNonNull(graph, "graph"));
    }

    public DagGraph graph() {
        return graph;
    }

    public List<TaskKey<?>> topologicalOrder() {
        return graph.topologicalOrder();
    }

    public boolean isDaemon(TaskKey<?> key) {
        graph.node(key);
        return nodes.get(key.id()).action() instanceof Daemon<?>;
    }

    public DagWorkflow select(List<? extends TaskKey<?>> roots) {
        var selected = graph.select(roots);
        var subset = new LinkedHashMap<String, Node<?>>();
        selected.topologicalOrder().forEach(key -> subset.put(key.id(), nodes.get(key.id())));
        return new DagWorkflow(subset, selected);
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
        private final DagGraph graph;
        private final DagGraph.Builder planning;

        private Builder(DagGraph graph) {
            this.graph = graph;
            planning = graph == null ? DagGraph.builder() : null;
        }

        public <T> Builder task(TaskKey<T> key, DagTask<T> task) {
            return task(key, needs(key), task, TaskOptions.DEFAULT);
        }

        public <T> Builder task(TaskKey<T> key, DagTask<T> task, TaskOptions options) {
            return task(key, needs(key), task, options);
        }

        public <T> Builder task(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                DagTask<T> task) {
            return task(key, needs, task, TaskOptions.DEFAULT);
        }

        public <T> Builder task(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                DagTask<T> task, TaskOptions options) {
            Objects.requireNonNull(task, "task");
            return add(key, needs, new Batch<>(task), options);
        }

        public <T> Builder daemon(TaskKey<T> key, DagDaemon<T> daemon) {
            return daemon(key, needs(key), daemon, DaemonOptions.DEFAULT, TaskOptions.DEFAULT);
        }

        public <T> Builder daemon(TaskKey<T> key, DagDaemon<T> daemon, DaemonOptions options) {
            return daemon(key, needs(key), daemon, options, TaskOptions.DEFAULT);
        }

        public <T> Builder daemon(TaskKey<T> key, DagDaemon<T> daemon,
                DaemonOptions options, TaskOptions attempts) {
            return daemon(key, needs(key), daemon, options, attempts);
        }

        public <T> Builder daemon(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                DagDaemon<T> daemon, DaemonOptions options) {
            return daemon(key, needs, daemon, options, TaskOptions.DEFAULT);
        }

        public <T> Builder daemon(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                DagDaemon<T> daemon, DaemonOptions options, TaskOptions attempts) {
            Objects.requireNonNull(daemon, "daemon");
            Objects.requireNonNull(options, "options");
            return add(key, needs, new Daemon<>(daemon, options), attempts);
        }

        private <T> Builder add(TaskKey<T> key, List<? extends TaskKey<?>> needs,
                Action<T> action, TaskOptions options) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(options, "options");
            List<TaskKey<?>> dependencies = List.copyOf(needs);
            if (planning != null) {
                planning.node(key, dependencies);
            } else if (!graph.node(key).needs().equals(dependencies)) {
                throw new IllegalArgumentException("Dependencies differ from the planned graph: " + key);
            }
            if (nodes.putIfAbsent(key.id(), new Node<>(key, action, options)) != null) {
                throw new IllegalArgumentException("Duplicate task ID: " + key.id());
            }
            return this;
        }

        public DagWorkflow build() {
            var planned = graph == null ? planning.build() : graph;
            if (nodes.size() != planned.topologicalOrder().size()) {
                throw new IllegalArgumentException("Every planned task must have an execution action.");
            }
            return new DagWorkflow(nodes, planned);
        }

        private List<TaskKey<?>> needs(TaskKey<?> key) {
            return graph == null ? List.of() : graph.node(key).needs();
        }
    }
}
