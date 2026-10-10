package io.adaptiveagents.ingress.common;

import io.adaptiveagents.core.envelope.TaskEnvelope;

/**
 * Protocol-neutral v1 envelope semantics — the SINGLE validation truth shared by every
 * ingress adapter (REST body, MCP tool args mapped into the same record, Kafka JSON body).
 * Jackson covers structure; these are the semantic rules beyond it:
 * <ul>
 *   <li>{@code task_type} must be {@code research} (the only v1 type);</li>
 *   <li>{@code breadth_hint} in 1..5 (the envelope default of 1 makes absence valid);</li>
 *   <li>{@code deadline_ms}/{@code budget_usd} null-or-positive;</li>
 *   <li>{@code prompt} non-blank.</li>
 * </ul>
 * Violations raise {@link TaskValidationException}; each adapter owns how the error reaches
 * its client (REST 400 problem+json, MCP tool error result, Kafka INVALID reply + DLT).
 */
public final class EnvelopeValidator {

    private EnvelopeValidator() {
    }

    public static void validate(TaskEnvelope envelope) {
        if (envelope.taskId() == null) {
            throw new TaskValidationException("task_id is required");
        }
        if (!"research".equals(envelope.taskType())) {
            throw new TaskValidationException("task_type must be 'research' (only v1 type supported)");
        }
        if (envelope.prompt() == null || envelope.prompt().isBlank()) {
            throw new TaskValidationException("prompt must not be blank");
        }
        if (envelope.breadthHint() < 1 || envelope.breadthHint() > 5) {
            throw new TaskValidationException("breadth_hint must be between 1 and 5");
        }
        if (envelope.deadlineMs() != null && envelope.deadlineMs() <= 0) {
            throw new TaskValidationException("deadline_ms must be greater than 0");
        }
        if (envelope.budgetUsd() != null && envelope.budgetUsd().signum() <= 0) {
            throw new TaskValidationException("budget_usd must be greater than 0");
        }
    }
}
