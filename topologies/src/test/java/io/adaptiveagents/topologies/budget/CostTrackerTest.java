package io.adaptiveagents.topologies.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.testutil.ScriptedChatModel;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §8.2 cost accounting: every recorded model response inserts one priced row into
 * {@code task_cost_entries}, accumulated spend is {@code SUM(cost_usd)}, and responses
 * without usage metadata (the Spring AI empty-usage placeholder) are skipped rather
 * than recorded as free work.
 *
 * <p>Prices pinned from the shipped {@code budget-prices.yml}: {@code gpt-4o-mini} at
 * input 0.15 / output 0.60 per 1M tokens (1,000,000 prompt + 500,000 completion tokens
 * cost {@code 0.15 + 0.30 = 0.45} USD), {@code default} at 1.00 / 2.00. Note that
 * SPECIFICATION.md §8.2 shows a default block of 0.15/0.60 — stale doc; the config's
 * own comment states the default exists to price unlisted model ids.</p>
 */
@Testcontainers
class CostTrackerTest {

    private static final String MODEL = "gpt-4o-mini";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbcTemplate;
    static TaskStore taskStore;
    static CostTracker costTracker;

    @BeforeAll
    static void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);

        Flyway.configure().dataSource(dataSource).load().migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        taskStore = new TaskStore(jdbcTemplate, new ObjectMapper(), txTemplate);
        costTracker = new CostTracker(jdbcTemplate);
    }

    @AfterAll
    static void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
    }

    private UUID newTask() {
        TaskEnvelope envelope = new TaskEnvelope(UUID.randomUUID(), "research", "cost probe", 1,
                null, null, null);
        return taskStore.submitOrGet(envelope).record().taskId();
    }

    /** Response with usage but no model id: priced by the default entry, recorded with "". */
    private static ChatResponse usageWithoutModel(long promptTokens, long completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage((int) promptTokens, (int) completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    }

    @Test
    void recordsPricedRowPerResponseAndSumsSpend() {
        UUID taskId = newTask();

        costTracker.record("search", taskId,
                ScriptedChatModel.textWithUsage("r1", MODEL, 1_000_000, 500_000));
        // §8.2 default tariffs: 1M × $0.15/1M + 0.5M × $0.60/1M = $0.45
        assertThat(costTracker.spentUsd(taskId)).isEqualByComparingTo(new BigDecimal("0.45"));

        costTracker.record("summarize", taskId,
                ScriptedChatModel.textWithUsage("r2", MODEL, 200_000, 0));
        // SUM(cost_usd) accumulates across nodes: 0.45 + 0.03 = 0.48
        assertThat(costTracker.spentUsd(taskId)).isEqualByComparingTo(new BigDecimal("0.48"));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT node_name, model, prompt_tokens, completion_tokens, cost_usd "
                        + "FROM task_cost_entries WHERE task_id = ? ORDER BY node_name",
                taskId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("node_name", "search")
                .containsEntry("model", MODEL)
                .containsEntry("prompt_tokens", 1_000_000L)
                .containsEntry("completion_tokens", 500_000L);
        assertThat((BigDecimal) rows.get(0).get("cost_usd"))
                .isEqualByComparingTo(new BigDecimal("0.45"));
    }

    @Test
    void freshTaskSpendsNothing() {
        UUID taskId = newTask();
        assertThat(costTracker.spentUsd(taskId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void skipsResponsesWithoutUsageMetadata() {
        UUID taskId = newTask();

        // no metadata at all — the empty-usage placeholder must be skipped, not priced
        costTracker.record("search", taskId, ScriptedChatModel.text("no usage"));
        assertThat(costTracker.spentUsd(taskId)).isEqualByComparingTo(BigDecimal.ZERO);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_cost_entries WHERE task_id = ?", Integer.class, taskId);
        assertThat(rows).isZero();
    }

    @Test
    void missingModelIdPricedByDefaultEntry() {
        UUID taskId = newTask();

        costTracker.record("plan", taskId, usageWithoutModel(1_000_000, 0));

        // SPEC DIVERGENCE: §8.2 shows the default block as 0.15/0.60 — that is the
        // gpt-4o-mini entry duplicated; the shipped config intentionally prices
        // unlisted/absent model ids at the higher default (1.00/2.00). See test-class javadoc.
        assertThat(costTracker.spentUsd(taskId)).isEqualByComparingTo(new BigDecimal("1.00"));
        String recordedModel = jdbcTemplate.queryForObject(
                "SELECT model FROM task_cost_entries WHERE task_id = ?", String.class, taskId);
        assertThat(recordedModel).isEmpty();
    }
}
