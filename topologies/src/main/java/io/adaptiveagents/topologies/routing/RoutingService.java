package io.adaptiveagents.topologies.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.classify.Band;
import io.adaptiveagents.core.classify.ComplexityClassifier;
import io.adaptiveagents.core.classify.ComplexityVerdict;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.IllegalTaskStateException;
import io.adaptiveagents.core.store.SubmitResult;
import io.adaptiveagents.core.store.TaskRecord;
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
     * <p>Runs under the per-task advisory lock ({@link TaskStore#withTaskLock}): a concurrent or
     * sequential duplicate submission blocks until the first routing commits, then resolves to the
     * recorded decision — 200-with-existing semantics, no second classification, no re-execution
     * trigger. The only re-routed duplicate is a task still in SUBMITTED (a previous attempt
     * crashed between the submission commit and the SUBMITTED-&gt;ROUTED transition); its routing
     * completes now, idempotently, under the same lock.
     *
     * <p>Decision-source derivation rule: a non-null {@code forcedTopology} (from any ingress) wins
     * and yields source OVERRIDE. Otherwise the classifier verdict supplies the topology, and the
     * source is LLM when the verdict band is AMBIGUOUS (the LLM leg decided; see the
     * {@code [llm-fallback]} rationale marker on the rule fallback path) and RULES otherwise.
     */
    public RoutingDecision route(TaskEnvelope envelope, long nowMs) {
        return taskStore.withTaskLock(envelope.taskId(), () -> routeLocked(envelope, nowMs));
    }

    private RoutingDecision routeLocked(TaskEnvelope envelope, long nowMs) {
        SubmitResult submission = taskStore.submitOrGet(envelope);

        if (!submission.created() && submission.record().status() != TaskStatus.SUBMITTED) {
            TaskRecord existing = submission.record();
            if (!existing.envelope().equals(envelope)) {
                log.warn("Task {} re-submitted with a different payload; keeping the original envelope",
                        envelope.taskId());
            }
            log.info("Duplicate submission for task {} in status {}; resolving to the recorded decision",
                    envelope.taskId(), existing.status());
            return new RoutingDecision(existing.topology(), storedDecisionSource(existing.decision()));
        }

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

    /**
     * Routing records the decision (with its source) before advancing to ROUTED, so every
     * non-SUBMITTED task has one; a missing or corrupt value is persisted-state corruption, not a
     * decision to guess at.
     */
    private DecisionSource storedDecisionSource(String decisionJson) {
        try {
            return DecisionSource.valueOf(MAPPER.readTree(decisionJson).path("source").asText());
        } catch (Exception e) {
            throw new IllegalTaskStateException("Recorded decision is missing or corrupt: " + decisionJson);
        }
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
