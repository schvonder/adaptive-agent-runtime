package io.adaptiveagents.topologies.budget;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-node token/cost tracker (T19): accumulates the USD cost of every model response a topology
 * node consumes, priced through the {@link PriceTable} loaded from {@code budget-prices.yml}.
 *
 * <p>Each recorded response inserts one row into {@code task_cost_entries} (node, model, tokens,
 * cost); the accumulated spend is {@code SUM(cost_usd)} over the task. Persisting per-response
 * rows means spend survives crash/resume — a restarted task never re-arms its budget.
 * {@link io.adaptiveagents.topologies.guard.TaskGuard} compares the spend against the envelope's
 * {@code budget_usd} at each node boundary; responses without usage metadata report 0/0 tokens and
 * are skipped rather than recorded as free work.</p>
 */
@Component
public final class CostTracker {

    private final JdbcTemplate jdbcTemplate;
    private final PriceTable priceTable;

    public CostTracker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceTable = PriceTable.fromClasspath();
    }

    /**
     * Records the cost of one model response consumed by a node. Responses that report no usage
     * (both token counts null, or both zero — the Spring AI empty-usage placeholder) are skipped.
     *
     * @param nodeName node that consumed the response (guard-boundary name)
     * @param taskId   running task whose budget is being consumed
     * @param response the model response carrying usage metadata
     */
    public void record(String nodeName, UUID taskId, ChatResponse response) {
        Integer promptTokens = response.getMetadata().getUsage().getPromptTokens();
        Integer completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        long prompt = promptTokens == null ? 0 : promptTokens;
        long completion = completionTokens == null ? 0 : completionTokens;
        if (prompt == 0 && completion == 0) {
            return;
        }
        String model = response.getMetadata().getModel();
        BigDecimal costUsd = priceTable.costUsd(model, prompt, completion);
        jdbcTemplate.update(
                "INSERT INTO task_cost_entries (task_id, node_name, model, prompt_tokens, completion_tokens, cost_usd) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                taskId, nodeName, model == null ? "" : model, prompt, completion, costUsd);
    }

    /** Accumulated spend for a task; {@code 0} when nothing has been recorded yet. */
    public BigDecimal spentUsd(UUID taskId) {
        BigDecimal spent = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(cost_usd), 0) FROM task_cost_entries WHERE task_id = ?",
                BigDecimal.class, taskId);
        return spent == null ? BigDecimal.ZERO : spent;
    }
}
