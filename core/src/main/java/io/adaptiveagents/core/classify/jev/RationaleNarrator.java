package io.adaptiveagents.core.classify.jev;

/**
 * Turns an already-made {@link JevDecision} into a human-readable rationale sentence for the
 * task state. Narrative only — the decision never depends on this call; a failing narrator is
 * logged and skipped by {@link JevClassifier}.
 */
public interface RationaleNarrator {

    String narrate(String state, JevDecision decision);
}
