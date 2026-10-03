package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.topologies.guard.NoOpGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopologyRegistryTest {

    // Wiring-only factory: no ChatClient.Builder needed to assert registry routing.
    private final TopologyRegistry registry =
            new TopologyRegistry(
                    new SoloGraphFactory(null, null, new NoOpGuard()),
                    new ChainGraphFactory(null, null, null, new NoOpGuard()),
                    new FanOutGraphFactory(null, null, new NoOpGuard()));

    @Test
    void mapsEveryTopologyToItsFactory() {
        assertThat(registry.factoryFor(Topology.SOLO)).isInstanceOf(SoloGraphFactory.class);
        assertThat(registry.factoryFor(Topology.CHAIN)).isInstanceOf(ChainGraphFactory.class);
        assertThat(registry.factoryFor(Topology.FAN_OUT)).isInstanceOf(FanOutGraphFactory.class);
        assertThat(registry.factoryFor(Topology.SUPERVISOR)).isInstanceOf(SupervisorGraphFactory.class);
    }

    @Test
    void factoriesAreDistinctPerTopology() {
        assertThat(registry.factoryFor(Topology.SOLO)).isNotSameAs(registry.factoryFor(Topology.CHAIN));
        assertThat(registry.factoryFor(Topology.FAN_OUT)).isNotSameAs(registry.factoryFor(Topology.SUPERVISOR));
    }

    @Test
    void unknownTopologyRejected() {
        assertThatThrownBy(() -> registry.factoryFor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }
}
