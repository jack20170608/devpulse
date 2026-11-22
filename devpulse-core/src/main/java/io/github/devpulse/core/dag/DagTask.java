package io.github.devpulse.core.dag;

@FunctionalInterface
public interface DagTask<T> {
    T execute(TaskContext context) throws Exception;
}
