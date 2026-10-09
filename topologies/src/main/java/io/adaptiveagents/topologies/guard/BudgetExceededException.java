package io.adaptiveagents.topologies.guard;

/**
 * Raised by the guard when a task's budget is exhausted at a node boundary: either the declared
 * {@code budget_usd} is non-positive, or the {@link io.adaptiveagents.topologies.budget.CostTracker}
 * spend accumulated from model responses has reached it.
 */
public class BudgetExceededException extends GuardException {
    public BudgetExceededException(String message) {
        super(message);
    }
}
