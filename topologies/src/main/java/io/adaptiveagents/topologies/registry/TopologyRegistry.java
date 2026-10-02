package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.core.model.Topology;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public final class TopologyRegistry {

    private final Map<Topology, GraphFactory> factories;

    public TopologyRegistry() {
        this.factories = Map.of(
                Topology.SOLO, new SoloGraphFactory(),
                Topology.CHAIN, new ChainGraphFactory(),
                Topology.FAN_OUT, new FanOutGraphFactory(),
                Topology.SUPERVISOR, new SupervisorGraphFactory()
        );
    }

    public GraphFactory factoryFor(Topology topology) {
        if (topology == null) {
            throw new IllegalArgumentException("topology must not be null");
        }
        GraphFactory factory = factories.get(topology);
        if (factory == null) {
            throw new IllegalArgumentException("No factory registered for topology: " + topology);
        }
        return factory;
    }
}
