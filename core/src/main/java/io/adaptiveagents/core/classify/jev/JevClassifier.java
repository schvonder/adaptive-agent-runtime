package io.adaptiveagents.core.classify.jev;

import io.adaptiveagents.core.classify.Band;
import io.adaptiveagents.core.classify.ComplexityClassifier;
import io.adaptiveagents.core.classify.ComplexityVerdict;
import io.adaptiveagents.core.classify.RuleScorer;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

/**
 * Jev (TypeSafe AI) leg of the complexity classifier, consulted only for the AMBIGUOUS rule band.
 *
 * <p>Mirrors {@link io.adaptiveagents.core.classify.LlmClassifier}: the rule score runs first and
 * clear bands return the rule verdict untouched — the decider is never called. For the AMBIGUOUS
 * band the decider resolves band and topology; unlike the LLM leg, Jev may de-ambiguate to any
 * band (a non-AMBIGUOUS Jev answer is accepted). The verdict always keeps the rule score.</p>
 *
 * <p>Fallback semantics (same contract the routing layer knows from the {@code [llm-fallback]}
 * marker): any decider failure or unparseable band/topology value returns the rule verdict with
 * rationale prefix {@code [jev-fallback: <msg>] }; a band or topology confidence below
 * {@code confidenceThreshold} returns the rule verdict with prefix
 * {@code [jev-low-confidence: band=<B> p=<p>, topology=<T> p=<p>] }.</p>
 *
 * <p>Rationale shape: always starts with {@code jev: band=<B> p=<%.2f>, topology=<T> p=<%.2f>};
 * rationale-mode=codes appends {@code ; reason=<code>}, rationale-mode=llm appends
 * {@code ; <narrator sentence>} (narrator failure is logged and drops the suffix). Fallback
 * verdicts replace this base with the marker prefix plus the rule rationale.</p>
 */
public class JevClassifier implements ComplexityClassifier {

    private static final Logger log = LoggerFactory.getLogger(JevClassifier.class);

    private final RuleScorer ruleScorer;
    private final JevTopologyDecider decider;
    private final double confidenceThreshold;
    private final String rationaleMode;
    private final RationaleNarrator narrator;

    public JevClassifier(
            RuleScorer ruleScorer,
            JevTopologyDecider decider,
            double confidenceThreshold,
            String rationaleMode,
            RationaleNarrator narrator) {
        this.ruleScorer = Objects.requireNonNull(ruleScorer, "ruleScorer");
        this.decider = Objects.requireNonNull(decider, "decider");
        this.confidenceThreshold = confidenceThreshold;
        this.rationaleMode = normalizeRationaleMode(rationaleMode);
        this.narrator = narrator;
    }

    /**
     * Validates and normalizes a rationale-mode property value (none|codes|llm, case-insensitive);
     * anything else fails fast — a silently mistyped mode would change verdict rationales.
     */
    public static String normalizeRationaleMode(String rationaleMode) {
        String normalized = rationaleMode == null ? "" : rationaleMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "codes", "llm" -> normalized;
            default -> throw new IllegalArgumentException(
                    "rationale-mode must be one of none|codes|llm, got: " + rationaleMode);
        };
    }

    @Override
    public ComplexityVerdict classify(TaskEnvelope envelope, long nowMs) {
        ComplexityVerdict ruleVerdict = ruleScorer.score(envelope, nowMs);
        if (ruleVerdict.band() != Band.AMBIGUOUS) {
            return ruleVerdict;
        }

        String state = envelope.prompt() + ". Breadth hint: " + envelope.breadthHint() + ".";

        JevDecision decision;
        try {
            decision = decider.decide(state);
        } catch (Exception e) {
            return fallback(ruleVerdict, "jev-fallback: " + e.getMessage());
        }

        if (decision.bandConfidence() < confidenceThreshold
                || decision.topologyConfidence() < confidenceThreshold) {
            return fallback(ruleVerdict, "jev-low-confidence: band=" + decision.band()
                    + " p=" + formatProbability(decision.bandConfidence())
                    + ", topology=" + decision.topology()
                    + " p=" + formatProbability(decision.topologyConfidence()));
        }

        Band band;
        Topology topology;
        try {
            band = Band.valueOf(decision.band());
            topology = Topology.valueOf(decision.topology());
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback(ruleVerdict, "jev-fallback: " + e.getMessage());
        }

        return new ComplexityVerdict(ruleVerdict.score(), band, topology, buildRationale(state, decision));
    }

    private ComplexityVerdict fallback(ComplexityVerdict ruleVerdict, String marker) {
        return new ComplexityVerdict(
                ruleVerdict.score(),
                ruleVerdict.band(),
                ruleVerdict.candidateTopology(),
                "[" + marker + "] " + ruleVerdict.rationale());
    }

    private String buildRationale(String state, JevDecision decision) {
        String base = "jev: band=" + decision.band()
                + " p=" + formatProbability(decision.bandConfidence())
                + ", topology=" + decision.topology()
                + " p=" + formatProbability(decision.topologyConfidence());
        String suffix = switch (rationaleMode) {
            case "codes" -> "; reason=" + decision.rationaleCode();
            case "llm" -> narratorSuffix(state, decision);
            default -> "";
        };
        return base + suffix;
    }

    private String narratorSuffix(String state, JevDecision decision) {
        if (narrator == null) {
            log.warn("rationale-mode=llm but no RationaleNarrator is wired; emitting the base Jev rationale only");
            return "";
        }
        try {
            String sentence = narrator.narrate(state, decision);
            return sentence.isBlank() ? "" : "; " + sentence;
        } catch (Exception e) {
            log.warn("Jev rationale narrator failed; emitting the base Jev rationale only: {}", e.getMessage());
            return "";
        }
    }

    private static String formatProbability(double probability) {
        return String.format(Locale.ROOT, "%.2f", probability);
    }
}
