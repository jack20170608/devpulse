package io.github.devpulse.core.dag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class TaskContext {
    private final String taskId;
    private final int attempt;
    private final Map<TaskKey<?>, Object> dependencies;
    private final BooleanSupplier cancellation;

    TaskContext(String taskId, int attempt, Map<TaskKey<?>, Object> dependencies,
            BooleanSupplier cancellation) {
        this.taskId = taskId;
        this.attempt = attempt;
        this.dependencies = Collections.unmodifiableMap(new LinkedHashMap<>(dependencies));
        this.cancellation = cancellation;
    }

    public String taskId() {
        return taskId;
    }

    public int attempt() {
        return attempt;
    }

    public <T> T result(TaskKey<T> dependency) {
        Objects.requireNonNull(dependency, "dependency");
        if (!dependencies.containsKey(dependency)) {
            throw new IllegalArgumentException(
                    "Not a declared direct dependency of " + taskId + ": " + dependency.id());
        }
        return dependency.resultType().cast(dependencies.get(dependency));
    }

    public boolean isCancellationRequested() {
        return cancellation.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    public void checkCancelled() throws InterruptedException {
        if (isCancellationRequested()) {
            throw new InterruptedException("Task " + taskId + " was interrupted or cancelled.");
        }
    }
}
