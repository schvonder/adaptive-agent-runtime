package io.adaptiveagents.core.classify.jev;

/**
 * Raw Jev decision for one task state: the chosen band/topology option names exactly as the
 * question defined them (parsed into enums by {@link JevClassifier}) with each answer's
 * confidence.
 *
 * @param rationaleCode nullable; populated only when the decider asked the "reason" question
 *                      (rationale-mode=codes)
 */
public record JevDecision(
        String band,
        double bandConfidence,
        String topology,
        double topologyConfidence,
        String rationaleCode) {}
