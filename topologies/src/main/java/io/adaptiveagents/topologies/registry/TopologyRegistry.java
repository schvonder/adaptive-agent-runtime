package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.core.model.Topology;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public final class TopologyRegistry {

    private final Map<Topology, GraphFactory> factories;

    public TopologyRegistry(SoloGraphFactory soloGraphFactory, ChainGraphFactory chainGraphFactory, FanOutGraphFactory fanOutGraphFactory, SupervisorGraphFactory supervisorGraphFactory) {
        this.factories = Map.of(
                Topology.SOLO, soloGraphFactory,
                Topology.CHAIN, chainGraphFactory,
                Topology.FAN_OUT, fanOutGraphFactory,
                Topology.SUPERVISOR, supervisorGraphFactory
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

    /**
     * Returns the uncompiled graph for a topology so the execution runner can compile it with a
     * checkpoint saver and a per-task thread id.
     */
    public StateGraph<? extends AgentState> graphFor(Topology topology) {
        return factoryFor(topology).graph();
    }
}
