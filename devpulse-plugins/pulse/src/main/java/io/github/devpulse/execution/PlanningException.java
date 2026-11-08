package io.github.devpulse.execution;

public final class PlanningException extends IllegalArgumentException {
    public enum Scope { WORKFLOW, TASK, PIPELINE, TARGET }

    private final Scope scope;
    private final String name;
    private final String field;

    PlanningException(Scope scope, String name, String field, String message) {
        super(message);
        this.scope = scope;
        this.name = name;
        this.field = field;
    }

    public Scope scope() {
        return scope;
    }

    public String name() {
        return name;
    }

    public String field() {
        return field;
    }
}
