package io.adaptiveagents.core.model;

public enum TaskStatus {
    SUBMITTED,
    ROUTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BUDGET_EXCEEDED,
    DEADLINE_EXCEEDED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == BUDGET_EXCEEDED || this == DEADLINE_EXCEEDED;
    }

    public boolean canTransitionTo(TaskStatus target) {
        if (this.isTerminal()) {
            return false;
        }
        return switch (this) {
            case SUBMITTED -> target == ROUTED;
            case ROUTED -> target == RUNNING;
            case RUNNING -> target.isTerminal();
            default -> false;
        };
    }
}
