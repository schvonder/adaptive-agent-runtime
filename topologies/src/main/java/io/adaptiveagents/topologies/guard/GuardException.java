package io.adaptiveagents.topologies.guard;

/**
 * Base exception for a per-node guard rejection: the node was refused execution at its boundary
 * (budget or deadline), as opposed to a generic failure inside the node.
 *
 * <p>Topology nodes catch this <em>separately</em> from generic exceptions and record the concrete
 * subclass name in the state under the {@code GUARD_REJECTED} key, so the execution runner can map
 * the run to the correct terminal status ({@code DEADLINE_EXCEEDED} / {@code BUDGET_EXCEEDED}).</p>
 */
public class GuardException extends RuntimeException {
    public GuardException(String message) {
        super(message);
    }
}
