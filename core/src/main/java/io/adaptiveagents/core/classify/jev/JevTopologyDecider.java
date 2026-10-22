package io.adaptiveagents.core.classify.jev;

/**
 * Resolves the AMBIGUOUS band to a {@link JevDecision} for a given task state.
 *
 * <p>Implementations may throw anything (SDK exception hierarchy, mapping failures);
 * {@link JevClassifier} owns the fallback and never lets an exception escape classification.</p>
 */
public interface JevTopologyDecider {

    JevDecision decide(String state);
}
