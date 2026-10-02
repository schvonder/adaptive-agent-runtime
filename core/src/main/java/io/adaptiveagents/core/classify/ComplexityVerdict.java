package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.model.Topology;

public record ComplexityVerdict(
        int score,
        Band band,
        Topology candidateTopology,
        String rationale
) {}
