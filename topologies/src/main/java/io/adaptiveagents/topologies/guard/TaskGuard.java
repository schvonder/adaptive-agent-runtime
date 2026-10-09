package io.adaptiveagents.topologies.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.budget.CostTracker;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Production {@link NodeGuard}: reports every node boundary and enforces deadline/budget.
 *
 * <p>At each node boundary it:</p>
 * <ol>
 *   <li>publishes a {@code NODE_START} progress event carrying the node name;</li>
 *   <li>raises {@link DeadlineExceededException} when the task has a declared {@code deadline_ms}
 *       and the wall clock is already past it;</li>
 *   <li>raises {@link BudgetExceededException} when the task has a declared {@code budget_usd}
 *       that is already non-positive, or when the {@link CostTracker} spend accumulated from model
 *       responses has reached it (T19).</li>
 * </ol>
 */
@Component
public final class TaskGuard implements NodeGuard {

    private final TaskStore taskStore;
    private final ProgressService progressService;
    private final ObjectMapper mapper;
    private final CostTracker costTracker;

    public TaskGuard(TaskStore taskStore, ProgressService progressService, ObjectMapper mapper, CostTracker costTracker) {
        this.taskStore = taskStore;
        this.progressService = progressService;
        this.mapper = mapper;
        this.costTracker = costTracker;
    }

    @Override
    public void check(String nodeName, UUID taskId) {
        publishNodeStart(nodeName, taskId);
        TaskRecord task = taskStore.find(taskId)
                .orElseThrow(() -> new IllegalStateException("Task not found for guard: " + taskId));

        Long deadlineMs = task.deadlineMs();
        if (deadlineMs != null && System.currentTimeMillis() > deadlineMs) {
            throw new DeadlineExceededException("deadline exceeded at node " + nodeName);
        }

        BigDecimal budgetUsd = task.budgetUsd();
        if (budgetUsd != null) {
            if (budgetUsd.signum() <= 0) {
                throw new BudgetExceededException("budget exhausted at node " + nodeName);
            }
            BigDecimal spent = costTracker.spentUsd(taskId);
            if (spent.compareTo(budgetUsd) >= 0) {
                throw new BudgetExceededException("budget exhausted at node " + nodeName + ": spent "
                        + money(spent) + " of " + money(budgetUsd));
            }
        }
    }

    private static String money(BigDecimal amount) {
        return "$" + amount.stripTrailingZeros().toPlainString();
    }

    private void publishNodeStart(String nodeName, UUID taskId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("node", nodeName);
        progressService.publish(taskId, "NODE_START", payload);
    }
}
