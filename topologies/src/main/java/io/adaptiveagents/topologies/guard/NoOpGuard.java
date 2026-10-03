package io.adaptiveagents.topologies.guard;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * No-op {@link NodeGuard} used until T16 wires the real budget/deadline checks.
 *
 * <p>Always permits execution. Replaced by a real guard in T16.</p>
 */
@Component
public final class NoOpGuard implements NodeGuard {

    @Override
    public void check(UUID taskId) {
        // Intentionally empty: real budget/deadline enforcement lands in T16.
    }
}
