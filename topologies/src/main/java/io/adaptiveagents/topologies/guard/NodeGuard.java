package io.adaptiveagents.topologies.guard;

import java.util.UUID;

/**
 * Seam for per-iteration budget/deadline enforcement inside topology nodes.
 *
 * <p>Topologies call {@link #check(UUID)} at each ReAct loop boundary. A throwing check aborts the
 * current run; implementations decide whether that means a hard failure or a degraded result.
 * Replaced by the real budget/deadline guard in T16.</p>
 */
public interface NodeGuard {

    /**
     * Verify the task may continue executing.
     *
     * @param taskId id of the running task
     * @throws RuntimeException when the task may not continue (budget/deadline exceeded)
     */
    void check(UUID taskId);
}
