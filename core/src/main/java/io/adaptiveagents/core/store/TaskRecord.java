package io.adaptiveagents.core.store;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskRecord(
        UUID taskId,
        TaskEnvelope envelope,
        TaskStatus status,
        Topology topology,
        String decision,
        String result,
        String error,
        BigDecimal budgetUsd,
        Long deadlineMs,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version
) {}
