package io.adaptiveagents.ingress.rest;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.store.SubmitResult;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.routing.RoutingService;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pipeline orchestration behind {@code POST /tasks}: idempotent submission, routing and
 * fire-and-forget execution dispatch.
 *
 * <p>Duplicate submissions mirror the store's T18 dedup semantics: {@code submitOrGet} resolves
 * them to the existing record with {@code created == false} and NO re-route, NO re-execution —
 * the caller answers 200 with the existing state. Only a newly created row is routed
 * (SUBMITTED -&gt; ROUTED) and handed to {@link ExecutionRunner#executeAsync(UUID)}.
 *
 * <p>The dispatch is deliberately decoupled from the HTTP request: a rejection or failure after
 * routing is logged and absorbed, never propagated to the caller. A routed-but-never-executed
 * row stays ROUTED — an acceptable transient; the startup rescan/sweep keep the system
 * convergent and a duplicate resubmission re-triggers nothing (by design).
 */
@Service
public class TaskIngressService {

    private static final Logger log = LoggerFactory.getLogger(TaskIngressService.class);

    private final TaskStore taskStore;
    private final RoutingService routingService;
    private final ExecutionRunner executionRunner;

    public TaskIngressService(TaskStore taskStore, RoutingService routingService, ExecutionRunner executionRunner) {
        this.taskStore = taskStore;
        this.routingService = routingService;
        this.executionRunner = executionRunner;
    }

    /** Submission outcome for the controller: the (current) record plus the created flag. */
    public record Submission(TaskRecord record, boolean created) {}

    public Submission submit(TaskEnvelope envelope) {
        SubmitResult submission = taskStore.submitOrGet(envelope);
        if (!submission.created()) {
            log.info("Duplicate submission for task {}; resolving to existing state ({})",
                    envelope.taskId(), submission.record().status());
            return new Submission(submission.record(), false);
        }
        routingService.route(envelope, System.currentTimeMillis());
        dispatch(envelope.taskId());
        // Re-read so the response reflects the freshest persisted status (ROUTED, or a later
        // status if the dispatched run already advanced it).
        TaskRecord current = taskStore.find(envelope.taskId()).orElse(submission.record());
        return new Submission(current, true);
    }

    /** Fire-and-forget dispatch; an executor rejection never fails the HTTP request after route. */
    private void dispatch(UUID taskId) {
        try {
            executionRunner.executeAsync(taskId).whenComplete((outcome, error) -> {
                if (error != null) {
                    log.warn("Task {} execution dispatch failed: {}", taskId, error.getMessage());
                } else {
                    TaskStatus status = outcome == null ? null : outcome.status();
                    log.info("Task {} execution finished with status {}", taskId, status);
                }
            });
        } catch (RuntimeException e) {
            log.error("Task {} dispatch was rejected after routing; row stays ROUTED (transient)", taskId, e);
        }
    }
}
