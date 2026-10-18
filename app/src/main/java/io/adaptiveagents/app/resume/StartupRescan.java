package io.adaptiveagents.app.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resumes tasks left RUNNING by a previous process death, or fails them over when their deadline
 * expired during the downtime.
 *
 * <p><strong>Why {@link ApplicationRunner} (and {@link Order}d highest) rather than
 * {@code SmartLifecycle}</strong>: an {@code ApplicationRunner} runs after the context refresh —
 * so Flyway has migrated and every bean it needs is ready — but still inside the startup call,
 * before any profile-specific runner that may block the startup thread (the {@code harness} probe
 * runner parks forever). Highest precedence keeps this component first in that runner chain. The
 * resumes themselves are dispatched fire-and-forget through {@link ExecutionRunner}'s unpooled
 * virtual-thread executor, so startup never waits on a graph: graph-level failures fold to FAILED
 * via the runner's outcome mapping, and the dispatch outcome is only logged here.</p>
 *
 * <p>Per RUNNING task, in deterministic {@code created_at} order: a deadline that has already
 * passed completes the task as {@link TaskStatus#DEADLINE_EXCEEDED} with a
 * {@code TASK_DEADLINE_EXCEEDED} progress event — the graph is never invoked; otherwise the task
 * is handed to {@link ExecutionRunner#startOrResumeAsync(UUID)}, which resumes from the last
 * checkpoint or, for a RUNNING task that never checkpointed, restarts with a fresh initial input.
 * With zero RUNNING tasks the rescan is a no-op. A failing rescan (per task or wholesale, e.g. the
 * database unreachable at boot) is logged and never aborts startup: RUNNING tasks stay persisted
 * for the next restart.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class StartupRescan implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRescan.class);

    private static final String TASK_DEADLINE_EXCEEDED = "TASK_DEADLINE_EXCEEDED";

    private final TaskStore taskStore;
    private final ProgressService progressService;
    private final ExecutionRunner executionRunner;
    private final ObjectMapper mapper;

    public StartupRescan(
            TaskStore taskStore,
            ProgressService progressService,
            ExecutionRunner executionRunner,
            ObjectMapper mapper) {
        this.taskStore = taskStore;
        this.progressService = progressService;
        this.executionRunner = executionRunner;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<TaskRecord> running = taskStore.findByStatus(TaskStatus.RUNNING);
            if (running.isEmpty()) {
                log.info("Startup rescan: no RUNNING tasks to resume");
                return;
            }
            log.info("Startup rescan: {} RUNNING task(s) to resume or fail over", running.size());
            for (TaskRecord task : running) {
                try {
                    dispatch(task);
                } catch (Exception e) {
                    log.warn("Startup rescan: decision for task {} failed (left as-is, retried on next restart): {}",
                            task.taskId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // A rescan failure (e.g. the database unreachable at boot) must not abort startup:
            // RUNNING tasks stay persisted and are picked up again on the next restart.
            log.error("Startup rescan failed; no tasks resumed this boot", e);
        }
    }

    private void dispatch(TaskRecord task) {
        if (expiredWhileDown(task)) {
            failOverExpired(task);
        } else {
            resume(task);
        }
    }

    private boolean expiredWhileDown(TaskRecord task) {
        Long deadlineMs = task.deadlineMs();
        return deadlineMs != null
                && System.currentTimeMillis() > task.createdAt().toInstant().toEpochMilli() + deadlineMs;
    }

    private void failOverExpired(TaskRecord task) {
        String reason = "deadline " + task.deadlineMs() + " passed while process was down";
        taskStore.completeTerminal(task.taskId(), TaskStatus.DEADLINE_EXCEEDED, errorJson(reason));
        progressService.publish(task.taskId(), TASK_DEADLINE_EXCEEDED, reasonPayload(reason));
        log.info("Startup rescan: task {} deadline expired while down -> DEADLINE_EXCEEDED (graph not invoked)",
                task.taskId());
    }

    private void resume(TaskRecord task) {
        UUID taskId = task.taskId();
        executionRunner.startOrResumeAsync(taskId).whenComplete((outcome, error) -> {
            if (error != null) {
                log.warn("Startup rescan: task {} resume dispatch failed: {}", taskId, error.getMessage());
            } else {
                log.info("Startup rescan: task {} finished with {}", taskId, outcome.status());
            }
        });
        log.info("Startup rescan: task {} redispatched (resume-or-restart) from previous run", taskId);
    }

    private String errorJson(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("message", message);
        return node.toString();
    }

    private ObjectNode reasonPayload(String reason) {
        ObjectNode node = mapper.createObjectNode();
        node.put("reason", reason);
        return node;
    }
}
