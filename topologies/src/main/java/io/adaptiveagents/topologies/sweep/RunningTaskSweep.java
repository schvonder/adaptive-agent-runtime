package io.adaptiveagents.topologies.sweep;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.IllegalTaskStateException;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic runtime sweep of RUNNING tasks whose deadline has expired — the runtime analogue of
 * the boot-time {@code StartupRescan}: where the rescan decides once at startup (fail over or
 * resume), this component keeps re-checking for as long as the process lives. It is the only
 * mechanism that can stop a run hung <em>mid-node</em> (a model call that never returns), because
 * boundary guards fire only between nodes.
 *
 * <p><strong>Who finalizes.</strong> Per expired RUNNING task there are two disjoint cases:</p>
 * <ul>
 *   <li>A local in-flight run exists — {@link ExecutionRunner#cancel(UUID)} returns true. The
 *       sweep only cancels; the runner's {@code CancellationException} branch then persists
 *       {@link TaskStatus#DEADLINE_EXCEEDED} and publishes {@code TASK_DEADLINE_EXCEEDED}, so
 *       persistence keeps a single owner per path.</li>
 *   <li>No local run (the row was left RUNNING by an abandoned or foreign run) — the sweep
 *       finalizes directly: {@code completeTerminal(DEADLINE_EXCEEDED)} plus a
 *       {@code TASK_DEADLINE_EXCEEDED} event whose reason records the no-local-run provenance;
 *       the graph is never invoked.</li>
 * </ul>
 *
 * <p><strong>Idempotency and failure isolation.</strong> Finalization races (boundary guard,
 * runner cancellation, another tick, a concurrent process) surface as
 * {@link IllegalTaskStateException} from the optimistic-locked terminal transition and are
 * absorbed at debug — the task is terminal either way, which is all a tick wants. The whole tick
 * is wrapped so it never propagates: any other failure is logged at warn and simply returns,
 * because an exception escaping a {@code @Scheduled} method would kill the fixed-delay chain and
 * silently disable every later sweep. RUNNING tasks without a deadline, or whose deadline has
 * not passed yet, are never touched.</p>
 */
@Component
public final class RunningTaskSweep {

    private static final Logger log = LoggerFactory.getLogger(RunningTaskSweep.class);

    private static final String TASK_DEADLINE_EXCEEDED = "TASK_DEADLINE_EXCEEDED";

    private final TaskStore taskStore;
    private final ProgressService progressService;
    private final ExecutionRunner executionRunner;
    private final ObjectMapper mapper;

    public RunningTaskSweep(
            TaskStore taskStore,
            ProgressService progressService,
            ExecutionRunner executionRunner,
            ObjectMapper mapper) {
        this.taskStore = taskStore;
        this.progressService = progressService;
        this.executionRunner = executionRunner;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${runtime.sweep.interval-ms:1000}")
    public void sweep() {
        try {
            for (TaskRecord task : taskStore.findByStatus(TaskStatus.RUNNING)) {
                try {
                    expireIfDue(task);
                } catch (IllegalTaskStateException alreadyTerminal) {
                    log.debug("Sweep: task {} already finalized elsewhere: {}",
                            task.taskId(), alreadyTerminal.getMessage());
                }
            }
        } catch (Exception e) {
            // Never propagate: an exception escaping a @Scheduled method stops the fixed-delay
            // chain, disabling all later sweeps. Next tick retries.
            log.warn("Sweep tick failed (retried on the next interval): {}", e.getMessage());
        }
    }

    private void expireIfDue(TaskRecord task) {
        Long deadlineMs = task.deadlineMs();
        if (deadlineMs == null || System.currentTimeMillis() <= deadlineMs) {
            return;
        }
        UUID taskId = task.taskId();
        if (executionRunner.cancel(taskId)) {
            log.info("Sweep: expired task {} had an in-flight run — cancelled, runner finalizes DEADLINE_EXCEEDED",
                    taskId);
            return;
        }
        String reason = "deadline " + deadlineMs + " expired with no local run (sweep)";
        taskStore.completeTerminal(taskId, TaskStatus.DEADLINE_EXCEEDED, errorJson("deadline expired; no local run"));
        progressService.publish(taskId, TASK_DEADLINE_EXCEEDED, reasonPayload(reason));
        log.info("Sweep: expired task {} had no local run -> DEADLINE_EXCEEDED (graph not invoked)", taskId);
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
