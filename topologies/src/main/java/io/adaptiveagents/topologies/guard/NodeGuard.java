package io.adaptiveagents.topologies.guard;

import java.util.UUID;

/**
 * Seam for per-node budget/deadline enforcement at every topology node boundary.
 *
 * <p>Topologies call {@link #check(String, UUID)} before each node runs. The node name identifies
 * the node (for progress reporting and diagnostics); the task id resolves the task's declared
 * budget/deadline. A throwing check aborts the current node; implementations decide whether that
 * means a hard failure or a degraded result. The concrete {@link GuardException} subclasses carry
 * the reason and are surfaced via the state's {@code GUARD_REJECTED} key.
 */
public interface NodeGuard {

    /**
     * Verify the task may execute this node.
     *
     * @param nodeName name of the node about to run (reported in progress events)
     * @param taskId   id of the running task
     * @throws GuardException when the node must not run (budget/deadline exceeded)
     */
    void check(String nodeName, UUID taskId);
}
