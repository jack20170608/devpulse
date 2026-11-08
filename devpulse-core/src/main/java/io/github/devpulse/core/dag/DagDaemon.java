package io.github.devpulse.core.dag;

@FunctionalInterface
public interface DagDaemon<T> {
    Handle<T> start(TaskContext context) throws Exception;

    enum Health { STARTING, READY, UNAVAILABLE, EXITED, UNKNOWN }

    interface Handle<T> {
        T value();

        Health check() throws Exception;
    }
}
