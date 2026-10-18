package io.adaptiveagents.ingress.common;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Row-by-row transcription of the §3.1 field-constraint table into the shared
 * {@link EnvelopeValidator} — the single validation truth behind the §12 row "schema
 * error is cut off before the database" (REST 400 / MCP isError / Kafka INVALID+DLT).
 *
 * <p>Valid rows: required fields present, {@code breadth_hint} boundaries 1 and 5,
 * null-or-positive {@code deadline_ms}/{@code budget_usd}, any listed topology.
 * Invalid rows: one per validator rule, each identified by its message fragment.</p>
 */
class EnvelopeValidatorTest {

    private static TaskEnvelope env(String taskType, String prompt, int breadth,
                                    Long deadlineMs, BigDecimal budgetUsd, Topology forced) {
        return new TaskEnvelope(UUID.randomUUID(), taskType, prompt, breadth,
                deadlineMs, budgetUsd, forced);
    }

    static Stream<Arguments> validEnvelopes() {
        return Stream.of(
                Arguments.of(env("research", "p", 1, null, null, null)),
                Arguments.of(env("research", "p", 5, null, null, null)),
                Arguments.of(env("research", "detailed prompt", 3, 1L,
                        new BigDecimal("0.01"), Topology.FAN_OUT)),
                Arguments.of(env("research", "p", 2, 3_600_000L,
                        new BigDecimal("10.00"), null))
        );
    }

    @ParameterizedTest(name = "valid: {0}")
    @MethodSource("validEnvelopes")
    void acceptsEnvelopesWithinSpecConstraints(TaskEnvelope envelope) {
        assertThatCode(() -> EnvelopeValidator.validate(envelope)).doesNotThrowAnyException();
    }

    static Stream<Arguments> invalidEnvelopes() {
        return Stream.of(
                Arguments.of(new TaskEnvelope(null, "research", "p", 1, null, null, null), "task_id"),
                Arguments.of(env(null, "p", 1, null, null, null), "task_type"),
                Arguments.of(env("summarize", "p", 1, null, null, null), "task_type"),
                Arguments.of(env("research", null, 1, null, null, null), "prompt"),
                Arguments.of(env("research", "   ", 1, null, null, null), "prompt"),
                Arguments.of(env("research", "p", 0, null, null, null), "breadth_hint"),
                Arguments.of(env("research", "p", 6, null, null, null), "breadth_hint"),
                Arguments.of(env("research", "p", 1, 0L, null, null), "deadline_ms"),
                Arguments.of(env("research", "p", 1, -1L, null, null), "deadline_ms"),
                Arguments.of(env("research", "p", 1, null, new BigDecimal("0.00"), null), "budget_usd"),
                Arguments.of(env("research", "p", 1, null, new BigDecimal("-0.50"), null), "budget_usd")
        );
    }

    @ParameterizedTest(name = "invalid: {1}")
    @MethodSource("invalidEnvelopes")
    void rejectsEnvelopesViolatingSpecConstraints(TaskEnvelope envelope, String ruleFragment) {
        assertThatThrownBy(() -> EnvelopeValidator.validate(envelope))
                .isInstanceOf(TaskValidationException.class)
                .hasMessageContaining(ruleFragment);
    }
}
