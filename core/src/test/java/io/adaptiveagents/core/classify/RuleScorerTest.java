package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class RuleScorerTest {

    private final RuleScorer scorer = new RuleScorer();

    private static String generatePrompt(int tokens) {
        // approx 4 chars per token
        return "a".repeat(tokens * 4);
    }

    private static TaskEnvelope env(int tokens, int breadth, Long deadlineMs) {
        return new TaskEnvelope(
                UUID.randomUUID(), 
                "research", 
                generatePrompt(tokens), 
                breadth, 
                deadlineMs, 
                null, 
                null
        );
    }
    
    private static TaskEnvelope envWithBudget(int tokens, int breadth, Long deadlineMs, BigDecimal budget) {
        return new TaskEnvelope(
                UUID.randomUUID(), 
                "research", 
                generatePrompt(tokens), 
                breadth, 
                deadlineMs, 
                budget, 
                null
        );
    }

    static Stream<Arguments> scoringCases() {
        long now = 1000000L;
        long tightDeadline = 500_000L; // 5 min duration
        long relaxedDeadline = 1_000_000L; // > 10 min duration
        
        return Stream.of(
            // tiny prompt (tokens<50, -10) + breadth1 (-10) -> score -20 -> CLEAR_SIMPLE, SOLO
            Arguments.of(env(10, 1, null), now, -20, Band.CLEAR_SIMPLE, Topology.SOLO),
            
            // minimal prompt (tokens<50, -10) + breadth1 (-10) -> score -20 -> CLEAR_SIMPLE, SOLO
            Arguments.of(env(0, 1, null), now, -20, Band.CLEAR_SIMPLE, Topology.SOLO),

            // breadth 5 (+20), normal prompt (tokens=100, 0) -> score 20 -> CLEAR_COMPLEX, SUPERVISOR
            Arguments.of(env(100, 5, null), now, 20, Band.CLEAR_COMPLEX, Topology.SUPERVISOR),
            
            // long prompt (tokens=600, +20), breadth 2 (+5) -> score 25 -> CLEAR_COMPLEX, FAN_OUT (since breadth < 4)
            Arguments.of(env(600, 2, null), now, 25, Band.CLEAR_COMPLEX, Topology.FAN_OUT),
            
            // tight deadline (+15), normal prompt (0), breadth 2 (+5) -> score 20 -> CLEAR_COMPLEX, FAN_OUT
            Arguments.of(env(100, 2, tightDeadline), now, 20, Band.CLEAR_COMPLEX, Topology.FAN_OUT),
            
            // tight deadline (+15), tiny prompt (-10), breadth 3 (+10) -> score 15 -> AMBIGUOUS, CHAIN
            Arguments.of(env(10, 3, tightDeadline), now, 15, Band.AMBIGUOUS, Topology.CHAIN),
            
            // no deadline (0), tiny prompt (-10), breadth 4 (+20) -> score 10 -> AMBIGUOUS, CHAIN
            Arguments.of(env(10, 4, null), now, 10, Band.AMBIGUOUS, Topology.CHAIN),
            
            // medium prompt (250, +10), breadth 1 (-10) -> score 0 -> CLEAR_SIMPLE, SOLO
            Arguments.of(env(250, 1, null), now, 0, Band.CLEAR_SIMPLE, Topology.SOLO),
            
            // relaxed deadline (0), medium prompt (250, +10), breadth 2 (+5) -> score 15 -> AMBIGUOUS, CHAIN
            Arguments.of(env(250, 2, relaxedDeadline), now, 15, Band.AMBIGUOUS, Topology.CHAIN),
            
            // boundary edge: exact 20 score (long prompt +20, breadth 1 -10, tight deadline +15 = 25) -> CLEAR_COMPLEX
            Arguments.of(env(600, 1, tightDeadline), now, 25, Band.CLEAR_COMPLEX, Topology.FAN_OUT),
            
            // boundary edge: exact 19 score (medium prompt +10, breadth 2 +5, tight deadline would be +15 but we don't have it, say breadth 3 is +10 = 20. 
            // Wait, let's make score 19. Can we make 19? No, our increments are multiples of 5. Max score under 20 is 15.
            Arguments.of(env(200, 2, null), now, 15, Band.AMBIGUOUS, Topology.CHAIN),
            
            // exact 1 score? (medium prompt +10, breadth 1 -10 -> 0; tight deadline +15 -> 15. 
            // how about 5? (breadth 2, normal prompt)
            Arguments.of(env(100, 2, null), now, 5, Band.AMBIGUOUS, Topology.CHAIN)
        );
    }

    @ParameterizedTest
    @MethodSource("scoringCases")
    void evaluateBandsAndTopologies(TaskEnvelope envelope, long nowMs, int expectedScore, Band expectedBand, Topology expectedTopology) {
        ComplexityVerdict verdict = scorer.score(envelope, nowMs);
        assertThat(verdict.score()).isEqualTo(expectedScore);
        assertThat(verdict.band()).isEqualTo(expectedBand);
        assertThat(verdict.candidateTopology()).isEqualTo(expectedTopology);
        
        // Negative assertion as requested: if it's CLEAR_COMPLEX and breadth 5, NOT SOLO
        if (envelope.breadthHint() == 5 && expectedBand == Band.CLEAR_COMPLEX) {
            assertThat(verdict.candidateTopology()).isNotEqualTo(Topology.SOLO);
        }
    }

    @Test
    void budgetIrrelevantToScore() {
        long now = 1000L;
        TaskEnvelope envWithout = env(200, 3, null);
        TaskEnvelope envWith = envWithBudget(200, 3, null, new BigDecimal("100.00"));
        
        ComplexityVerdict v1 = scorer.score(envWithout, now);
        ComplexityVerdict v2 = scorer.score(envWith, now);
        
        assertThat(v1.score()).isEqualTo(v2.score());
        assertThat(v1.band()).isEqualTo(v2.band());
        assertThat(v1.candidateTopology()).isEqualTo(v2.candidateTopology());
        assertThat(v1.rationale()).isEqualTo(v2.rationale());
    }
    
    @Test
    void determinismTest() {
        TaskEnvelope env = env(350, 4, 1500000L);
        long now = 1000000L;
        
        ComplexityVerdict first = scorer.score(env, now);
        
        for (int i = 0; i < 10; i++) {
            ComplexityVerdict subsequent = scorer.score(env, now);
            assertThat(subsequent.score()).isEqualTo(first.score());
            assertThat(subsequent.band()).isEqualTo(first.band());
            assertThat(subsequent.candidateTopology()).isEqualTo(first.candidateTopology());
            assertThat(subsequent.rationale()).isEqualTo(first.rationale());
        }
    }
}
