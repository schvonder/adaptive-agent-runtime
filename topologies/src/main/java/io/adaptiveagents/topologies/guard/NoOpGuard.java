package io.adaptiveagents.topologies.guard;

import java.util.UUID;

/**
 * No-op {@link NodeGuard} used in wiring-only contexts (e.g. unit tests that assert registry
 * routing without a database-backed task).
 *
 * <p>Always permits execution. Not registered as a Spring bean — the production bean is
 * {@link TaskGuard} (T16).</p>
 */
public final class NoOpGuard implements NodeGuard {

    @Override
    public void check(String nodeName, UUID taskId) {
        // Intentionally empty: used only where no task exists to guard.
    }
}
