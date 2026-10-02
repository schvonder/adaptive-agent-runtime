package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;

import java.util.ArrayList;
import java.util.List;

/**
 * RuleScorer computes a deterministic complexity score for a TaskEnvelope.
 *
 * Weights:
 * - Tokens (length / 4): 
 *   - < 50 tokens: -10
 *   - > 500 tokens: +20
 *   - 200-500 tokens: +10
 * - Breadth Hint (1..5):
 *   - 1: -10
 *   - 2: +5
 *   - 3: +10
 *   - 4 or 5: +20
 * - Deadline Tightness:
 *   - (deadlineMs - nowMs) < 600,000 ms (10 min): +15
 *
 * Bands:
 * - Score <= 0: CLEAR_SIMPLE (SOLO)
 * - Score 1 to 19: AMBIGUOUS (CHAIN)
 * - Score >= 20: CLEAR_COMPLEX (SUPERVISOR if breadthHint >= 4, else FAN_OUT)
 */
public class RuleScorer {

    private static final long TIGHT_DEADLINE_MS = 600_000L;

    public ComplexityVerdict score(TaskEnvelope envelope, long nowMs) {
        int score = 0;
        List<String> rationales = new ArrayList<>();

        int tokens = envelope.prompt().length() / 4;
        if (tokens < 50) {
            score -= 10;
            rationales.add("Short prompt (<50 tokens) [-10]");
        } else if (tokens > 500) {
            score += 20;
            rationales.add("Long prompt (>500 tokens) [+20]");
        } else if (tokens >= 200) {
            score += 10;
            rationales.add("Medium prompt (>=200 tokens) [+10]");
        }

        int breadth = envelope.breadthHint();
        if (breadth <= 1) {
            score -= 10;
            rationales.add("Breadth 1 [-10]");
        } else if (breadth == 2) {
            score += 5;
            rationales.add("Breadth 2 [+5]");
        } else if (breadth == 3) {
            score += 10;
            rationales.add("Breadth 3 [+10]");
        } else if (breadth >= 4) {
            score += 20;
            rationales.add("Breadth >= 4 [+20]");
        }

        if (envelope.deadlineMs() != null) {
            long remaining = envelope.deadlineMs() - nowMs;
            if (remaining > 0 && remaining < TIGHT_DEADLINE_MS) {
                score += 15;
                rationales.add("Tight deadline (<10m remaining) [+15]");
            }
        }

        Band band;
        Topology candidateTopology;

        if (score <= 0) {
            band = Band.CLEAR_SIMPLE;
            candidateTopology = Topology.SOLO;
        } else if (score < 20) {
            band = Band.AMBIGUOUS;
            candidateTopology = Topology.CHAIN;
        } else {
            band = Band.CLEAR_COMPLEX;
            candidateTopology = breadth >= 4 ? Topology.SUPERVISOR : Topology.FAN_OUT;
        }

        String rationaleStr = String.join(", ", rationales);
        if (rationaleStr.isEmpty()) {
            rationaleStr = "No rules triggered";
        }

        return new ComplexityVerdict(score, band, candidateTopology, rationaleStr);
    }
}
