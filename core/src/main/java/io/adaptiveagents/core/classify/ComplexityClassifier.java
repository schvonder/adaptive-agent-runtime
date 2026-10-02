package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;

public interface ComplexityClassifier {
    ComplexityVerdict classify(TaskEnvelope envelope, long nowMs);
}
