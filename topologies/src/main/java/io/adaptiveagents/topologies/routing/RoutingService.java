package io.adaptiveagents.topologies.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.classify.Band;
import io.adaptiveagents.core.classify.ComplexityClassifier;
import io.adaptiveagents.core.classify.ComplexityVerdict;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskStore taskStore;
    private final ComplexityClassifier classifier;

    public RoutingService(TaskStore taskStore, ComplexityClassifier classifier) {
        this.taskStore = taskStore;
        this.classifier = classifier;
    }

    /**
     * Routes a task to a topology and persists the decision, advancing status SUBMITTED -> ROUTED.
     *
     * <p>Decision-source derivation rule: a non-null {@code forcedTopology} (from any ingress) wins
     * and yields source OVERRIDE. Otherwise the classifier verdict supplies the topology, and the
     * source is LLM when the verdict band is AMBIGUOUS (the LLM leg decided; see the
     * {@code [llm-fallback]} rationale marker on the rule fallback path) and RULES otherwise.
     */
    public RoutingDecision route(TaskEnvelope envelope, long nowMs) {
        taskStore.submitOrGet(envelope);

        Topology topology;
        DecisionSource source;
        String rationale;

        if (envelope.forcedTopology() != null) {
            topology = envelope.forcedTopology();
            source = DecisionSource.OVERRIDE;
            rationale = "forced_topology override from ingress";
            taskStore.recordDecision(envelope.taskId(), topology, buildDecisionJson(null, source, rationale));
        } else {
            ComplexityVerdict verdict = classifier.classify(envelope, nowMs);
            topology = verdict.candidateTopology();
            source = verdict.band() == Band.AMBIGUOUS ? DecisionSource.LLM : DecisionSource.RULES;
            rationale = verdict.rationale();
            taskStore.recordDecision(envelope.taskId(), topology, buildDecisionJson(verdict, source, rationale));
        }

        taskStore.transition(envelope.taskId(), TaskStatus.ROUTED);
        log.info("Routed task {} to {} (source={})", envelope.taskId(), topology, source);
        return new RoutingDecision(topology, source);
    }

    private String buildDecisionJson(ComplexityVerdict verdict, DecisionSource source, String rationale) {
        ObjectNode decision = MAPPER.createObjectNode();
        if (verdict == null) {
            decision.putNull("verdict");
        } else {
            ObjectNode v = decision.putObject("verdict");
            v.put("score", verdict.score());
            v.put("band", verdict.band().name());
            v.put("candidate_topology", verdict.candidateTopology().name());
            v.put("rationale", verdict.rationale());
        }
        decision.put("source", source.name());
        decision.put("rationale", rationale);
        return decision.toString();
    }
}
