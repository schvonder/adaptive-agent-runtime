package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row-by-row transcription of the SPECIFICATION.md weight and band tables into
 * parameterized cases, one row per table entry plus each boundary value.
 *
 * <p>Source tables:</p>
 * <ul>
 *   <li>§5.1 W_tokens: &lt;50 tokens → −10; 50..199 → 0; 200..500 → +10; &gt;500 → +20
 *       (tokens ≈ chars / 4).</li>
 *   <li>§5.1 W_breadth: ≤1 → −10; 2 → +5; 3 → +10; ≥4 → +20.</li>
 * <li>§5.1 W_deadline: declared duration &lt; 600,000 ms → +15; absent or ≥ 600,000 → 0.</li>
 * <li>§5.2 bands: ≤0 → CLEAR_SIMPLE/SOLO; 1..19 → AMBIGUOUS (candidate CHAIN);
 *       ≥20 → CLEAR_COMPLEX (breadth &lt;4 → FAN_OUT, ≥4 → SUPERVISOR).</li>
 * </ul>
 *
 * <p>{@code deadline_ms} is a <em>duration from task creation</em> per §3.1/§8.1
 * ({@code createdAt + deadlineMs}); the scorer weighs the declared duration itself and the
 * {@code nowMs} seam parameter does not participate in the deadline weight.</p>
 */
public class RuleScorerTableTest {

    private static final long NOW = 1_000_000L;

    private final RuleScorer scorer = new RuleScorer();

    /** prompt of exactly {@code tokens} estimated tokens (4 chars per token, integer division). */
    private static String prompt(int tokens) {
        return "a".repeat(tokens * 4);
    }

    private static TaskEnvelope env(int tokens, int breadth, Long deadlineMs) {
        return new TaskEnvelope(UUID.randomUUID(), "research", prompt(tokens), breadth,
                deadlineMs, null, null);
    }

    /** §5.1 W_tokens rows, isolated with breadth=2 (+5) and no deadline. */
    static Stream<Arguments> tokenWeightRows() {
        return Stream.of(
                Arguments.of(0, -10),    // degenerate empty side of "< 50 tokens"
                Arguments.of(49, -10),   // last row of "< 50 tokens" band
                Arguments.of(50, 0),     // first row of "50..199" band (200 chars)
                Arguments.of(199, 0),    // last row of "50..199" band
                Arguments.of(200, +10),  // first row of "200..500" band (800 chars)
                Arguments.of(500, +10),  // last row of "200..500" band (2000 chars)
                Arguments.of(501, +20),  // first row of "> 500" band
                Arguments.of(2_000, +20) // deep into "> 500" band
        );
    }

    @ParameterizedTest(name = "tokens={0} -> weight {1}")
    @MethodSource("tokenWeightRows")
    void tokenWeightTable(int tokens, int expectedTokenWeight) {
        ComplexityVerdict verdict = scorer.score(env(tokens, 2, null), NOW);
        // breadth=2 contributes +5 per §5.1; expected total = token weight + 5.
        assertThat(verdict.score()).isEqualTo(expectedTokenWeight + 5);
    }

    /** §5.1 W_breadth rows, isolated with a 100-token prompt (weight 0) and no deadline. */
    static Stream<Arguments> breadthWeightRows() {
        return Stream.of(
                Arguments.of(1, -10),  // "breadth_hint <= 1" (default hint is 1)
                Arguments.of(2, +5),
                Arguments.of(3, +10),
                Arguments.of(4, +20),  // first row of ">= 4"
                Arguments.of(5, +20)   // upper validation bound (envelope max)
        );
    }

    @ParameterizedTest(name = "breadth={0} -> weight {1}")
    @MethodSource("breadthWeightRows")
    void breadthWeightTable(int breadth, int expectedBreadthWeight) {
        ComplexityVerdict verdict = scorer.score(env(100, breadth, null), NOW);
        assertThat(verdict.score()).isEqualTo(expectedBreadthWeight);
    }

    /** §5.1 W_deadline rows over the declared duration itself (§3.1: duration from creation). */
    static Stream<Arguments> deadlineWeightRows() {
        return Stream.of(
                // absent deadline -> 0
                Arguments.of(null, 0),
                // duration exactly at the 10-minute boundary: spec says strictly "< 600,000" -> 0
                Arguments.of(600_000L, 0),
                // duration just inside the tight window -> +15
                Arguments.of(599_999L, +15),
                // the smallest positive duration an envelope can declare
                Arguments.of(1L, +15),
                // comfortably above the window -> 0
                Arguments.of(600_001L, 0),
                Arguments.of(3_600_000L, 0)
        );
    }

    @ParameterizedTest(name = "deadline_ms={0} -> weight {1}")
    @MethodSource("deadlineWeightRows")
    void deadlineWeightTable(Long deadlineMs, int expectedDeadlineWeight) {
        ComplexityVerdict verdict = scorer.score(env(100, 2, deadlineMs), NOW);
        // prompt weight 0 + breadth=2 (+5) + deadline weight
        assertThat(verdict.score()).isEqualTo(expectedDeadlineWeight + 5);
    }

    /** §5.2 band/topology mapping on both sides of every band boundary. */
    static Stream<Arguments> bandRows() {
        return Stream.of(
                // score = 0 (prompt 250 -> +10, breadth 1 -> -10): last score of CLEAR_SIMPLE
                Arguments.of(250, 1, null, 0, Band.CLEAR_SIMPLE, Topology.SOLO),
                // score = -20 (all negative weights): deep CLEAR_SIMPLE
                Arguments.of(10, 1, null, -20, Band.CLEAR_SIMPLE, Topology.SOLO),
                // score = 5 (breadth 2 only): first reachable AMBIGUOUS score
                Arguments.of(100, 2, null, 5, Band.AMBIGUOUS, Topology.CHAIN),
                // score = 15: highest reachable AMBIGUOUS score (increments are multiples of 5)
                Arguments.of(200, 2, null, 15, Band.AMBIGUOUS, Topology.CHAIN),
                // score = 20, breadth 3: CLEAR_COMPLEX boundary, breadth < 4 -> FAN_OUT
                Arguments.of(600, 3, null, 30, Band.CLEAR_COMPLEX, Topology.FAN_OUT),
                // score = 20, breadth 4: CLEAR_COMPLEX boundary, breadth >= 4 -> SUPERVISOR
                Arguments.of(100, 4, null, 20, Band.CLEAR_COMPLEX, Topology.SUPERVISOR),
                // score = 20, breadth 5 -> SUPERVISOR
                Arguments.of(100, 5, null, 20, Band.CLEAR_COMPLEX, Topology.SUPERVISOR)
        );
    }

    @ParameterizedTest(name = "tokens={0}, breadth={1}, deadline={2} -> {3} {4}/{5}")
    @MethodSource("bandRows")
    void bandTopologyTable(int tokens, int breadth, Long deadlineMs, int expectedScore,
                           Band expectedBand, Topology expectedTopology) {
        ComplexityVerdict verdict = scorer.score(env(tokens, breadth, deadlineMs), NOW);
        assertThat(verdict.score()).isEqualTo(expectedScore);
        assertThat(verdict.band()).isEqualTo(expectedBand);
        assertThat(verdict.candidateTopology()).isEqualTo(expectedTopology);
    }
}
