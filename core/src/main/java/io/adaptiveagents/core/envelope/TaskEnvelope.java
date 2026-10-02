package io.adaptiveagents.core.envelope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.adaptiveagents.core.model.Topology;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskEnvelope(
        @JsonProperty(value = "task_id", required = true) UUID taskId,
        @JsonProperty(value = "task_type", required = true) String taskType,
        @JsonProperty(value = "prompt", required = true) String prompt,
        @JsonProperty(value = "breadth_hint") int breadthHint,
        @JsonProperty(value = "deadline_ms") Long deadlineMs,
        @JsonProperty(value = "budget_usd") BigDecimal budgetUsd,
        @JsonProperty(value = "forced_topology") Topology forcedTopology) {

    @JsonCreator
    public TaskEnvelope(
            @JsonProperty(value = "task_id", required = true) UUID taskId,
            @JsonProperty(value = "task_type", required = true) String taskType,
            @JsonProperty(value = "prompt", required = true) String prompt,
            @JsonProperty(value = "breadth_hint") Integer breadthHint,
            @JsonProperty(value = "deadline_ms") Long deadlineMs,
            @JsonProperty(value = "budget_usd") BigDecimal budgetUsd,
            @JsonProperty(value = "forced_topology") Topology forcedTopology) {
        this(taskId, 
             taskType, 
             prompt, 
             breadthHint == null ? 1 : breadthHint, 
             deadlineMs, 
             budgetUsd, 
             forcedTopology);
    }
}
