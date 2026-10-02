package io.adaptiveagents.topologies.routing;

import io.adaptiveagents.core.model.Topology;

public record RoutingDecision(Topology topology, DecisionSource source) {}
