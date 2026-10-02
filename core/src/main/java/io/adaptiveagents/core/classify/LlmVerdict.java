package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.model.Topology;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmVerdict(Band band, Topology topology, String rationale) {}
