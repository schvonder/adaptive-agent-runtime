package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("stub-classifier")
@Component
public class StubClassifier implements ComplexityClassifier {

    @Override
    public ComplexityVerdict classify(TaskEnvelope envelope, long nowMs) {
        // Deterministic stub mapping
        Band band = Band.CLEAR_SIMPLE;
        Topology topo = Topology.SOLO;
        
        if (envelope.breadthHint() >= 4) {
            band = Band.CLEAR_COMPLEX;
            topo = Topology.SUPERVISOR;
        } else if (envelope.breadthHint() > 1) {
            band = Band.AMBIGUOUS;
            topo = Topology.CHAIN;
        }
        
        return new ComplexityVerdict(10, band, topo, "Stub classifier rationale");
    }
}
