package io.adaptiveagents.topologies.guard;

/**
 * Raised by the guard when a task's deadline has already passed at a node boundary.
 */
public class DeadlineExceededException extends GuardException {
    public DeadlineExceededException(String message) {
        super(message);
    }
}
