package io.adaptiveagents.topologies.registry;

/**
 * Seam for constructing a compiled topology graph.
 * Concrete graph assembly (langgraph4j StateGraph) lands in T12-T15; T10 registers one stub factory per topology.
 */
public interface GraphFactory {
    Object build();
}
