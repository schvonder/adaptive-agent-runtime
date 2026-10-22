package io.adaptiveagents.core.classify.jev;

import io.adaptiveagents.core.classify.Band;
import io.adaptiveagents.core.classify.ComplexityVerdict;
import io.adaptiveagents.core.classify.RuleScorer;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-JUnit behavioral test for {@link JevClassifier} with stubbed decider/narrator and the
 * real {@link RuleScorer}. Envelope recipes follow LlmClassifierWireMockTest: breadth=2 with
 * prompt length 400 lands in AMBIGUOUS (score 5, CHAIN), breadth=1 with length 40 in
 * CLEAR_SIMPLE (-20, SOLO).
 */
class JevClassifierTest {

    private final RuleScorer ruleScorer = new RuleScorer();

    private TaskEnvelope createEnvelope(int breadth, int promptLength) {
        return new TaskEnvelope(UUID.randomUUID(), "research", "a".repeat(promptLength), breadth, null, null, null);
    }

    private static final class StubDecider implements JevTopologyDecider {
        private final JevDecision decision;
        private final RuntimeException failure;
        private int calls;
        private String lastState;

        StubDecider(JevDecision decision, RuntimeException failure) {
            this.decision = decision;
            this.failure = failure;
        }

        @Override
        public JevDecision decide(String state) {
            calls++;
            lastState = state;
            if (failure != null) {
                throw failure;
            }
            return decision;
        }
    }

    private static final class StubNarrator implements RationaleNarrator {
        private final String sentence;
        private final RuntimeException failure;

        StubNarrator(String sentence, RuntimeException failure) {
            this.sentence = sentence;
            this.failure = failure;
        }

        @Override
        public String narrate(String state, JevDecision decision) {
            if (failure != null) {
                throw failure;
            }
            return sentence;
        }
    }

    private static JevDecision decision(String band, double bandConfidence, String topology, double topologyConfidence) {
        return new JevDecision(band, bandConfidence, topology, topologyConfidence, "CONFLICTING_SIGNALS");
    }

    @Test
    void clearBandsNeverCallDecider() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.9, "CHAIN", 0.9), null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "codes", null);

        // CLEAR_SIMPLE: tiny prompt (40 chars -> 10 tokens, -10), breadth 1 (-10) => -20
        ComplexityVerdict simple = classifier.classify(createEnvelope(1, 40), 1000L);
        assertThat(simple.band()).isEqualTo(Band.CLEAR_SIMPLE);
        assertThat(simple.candidateTopology()).isEqualTo(Topology.SOLO);

        // CLEAR_COMPLEX: breadth 5 (+20), prompt length 400 => 20
        ComplexityVerdict complex = classifier.classify(createEnvelope(5, 400), 1000L);
        assertThat(complex.band()).isEqualTo(Band.CLEAR_COMPLEX);
        assertThat(complex.candidateTopology()).isEqualTo(Topology.SUPERVISOR);

        assertThat(decider.calls).isZero();
    }

    @Test
    void confidentDecisionUsesJevBandAndTopology() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.92, "FAN_OUT", 0.88), null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "none", null);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(decider.calls).isEqualTo(1);
        assertThat(decider.lastState).contains("a".repeat(400));
        assertThat(decider.lastState).contains("Breadth hint: 2");
        assertThat(verdict.score()).isEqualTo(5);
        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.FAN_OUT);
        assertThat(verdict.rationale())
                .isEqualTo("jev: band=AMBIGUOUS p=0.92, topology=FAN_OUT p=0.88");
    }

    @Test
    void jevMayDeAmbiguateToClearBand() {
        StubDecider decider = new StubDecider(decision("CLEAR_COMPLEX", 0.95, "SUPERVISOR", 0.9), null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "none", null);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.band()).isEqualTo(Band.CLEAR_COMPLEX);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.SUPERVISOR);
        assertThat(verdict.score()).isEqualTo(5);
        assertThat(verdict.rationale()).startsWith("jev: band=CLEAR_COMPLEX p=0.95");
    }

    @Test
    void codesModeAppendsReasonCode() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.92, "FAN_OUT", 0.88), null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "codes", null);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.rationale())
                .isEqualTo("jev: band=AMBIGUOUS p=0.92, topology=FAN_OUT p=0.88; reason=CONFLICTING_SIGNALS");
    }

    @Test
    void llmModeAppendsNarratorSentence() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.92, "FAN_OUT", 0.88), null);
        StubNarrator narrator = new StubNarrator("The breadth hint outgrows the narrow prompt.", null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "llm", narrator);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.rationale()).isEqualTo(
                "jev: band=AMBIGUOUS p=0.92, topology=FAN_OUT p=0.88; The breadth hint outgrows the narrow prompt.");
    }

    @Test
    void llmModeNarratorFailureKeepsBaseRationaleOnly() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.92, "FAN_OUT", 0.88), null);
        StubNarrator narrator = new StubNarrator(null, new RuntimeException("narrator down"));
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "llm", narrator);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.FAN_OUT);
        assertThat(verdict.rationale()).isEqualTo("jev: band=AMBIGUOUS p=0.92, topology=FAN_OUT p=0.88");
    }

    @Test
    void lowBandConfidenceFallsBackToRules() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.40, "FAN_OUT", 0.90), null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "codes", null);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(verdict.rationale())
                .startsWith("[jev-low-confidence: band=AMBIGUOUS p=0.40, topology=FAN_OUT p=0.90] ");
        assertThat(verdict.rationale()).contains("Breadth 2 [+5]");
    }

    @Test
    void lowTopologyConfidenceFallsBackToRules() {
        StubDecider decider = new StubDecider(decision("AMBIGUOUS", 0.90, "FAN_OUT", 0.55), null);
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "codes", null);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(verdict.rationale())
                .startsWith("[jev-low-confidence: band=AMBIGUOUS p=0.90, topology=FAN_OUT p=0.55] ");
        assertThat(verdict.rationale()).contains("Breadth 2 [+5]");
    }

    @Test
    void deciderFailureFallsBackToRules() {
        StubDecider decider = new StubDecider(null, new RuntimeException("jev unreachable"));
        JevClassifier classifier = new JevClassifier(ruleScorer, decider, 0.6, "codes", null);

        ComplexityVerdict verdict = classifier.classify(createEnvelope(2, 400), 1000L);

        assertThat(verdict.score()).isEqualTo(5);
        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(verdict.rationale()).startsWith("[jev-fallback: jev unreachable] ");
        assertThat(verdict.rationale()).contains("Breadth 2 [+5]");
    }

    @Test
    void invalidBandOrTopologyValueFallsBackToRules() {
        JevClassifier classifier =
                new JevClassifier(ruleScorer, new StubDecider(decision("SOMEHOW", 0.9, "CHAIN", 0.9), null), 0.6, "codes", null);
        ComplexityVerdict badBand = classifier.classify(createEnvelope(2, 400), 1000L);
        assertThat(badBand.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(badBand.rationale()).startsWith("[jev-fallback: ");

        classifier = new JevClassifier(
                ruleScorer, new StubDecider(decision("AMBIGUOUS", 0.9, "TOPOLOGY_TEAM", 0.9), null), 0.6, "codes", null);
        ComplexityVerdict badTopology = classifier.classify(createEnvelope(2, 400), 1000L);
        assertThat(badTopology.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(badTopology.rationale()).startsWith("[jev-fallback: ");
    }
}
