package io.adaptiveagents.topologies.guard;

/**
 * Raised by the guard when a task's budget is already exhausted at a node boundary.
 *
 * <p>This is the structural placeholder: the full per-node token/cost accounting lands in T19, so
 * today the guard only rejects a task whose declared {@code budget_usd} is already non-positive.
 * See {@code TaskGuard} javadoc.</p>
 */
public class BudgetExceededException extends GuardException {
    public BudgetExceededException(String message) {
        super(message);
    }
}
