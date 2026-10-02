package io.adaptiveagents.core.progress;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProgressEvent(
        long seq,
        UUID taskId,
        String type,
        String payload,
        OffsetDateTime createdAt
) {}
