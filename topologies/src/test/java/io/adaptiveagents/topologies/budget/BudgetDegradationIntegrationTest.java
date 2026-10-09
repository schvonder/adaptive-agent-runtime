package io.adaptiveagents.topologies.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.registry.FanOutGraphFactory;
import io.adaptiveagents.topologies.routing.RoutingService;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import io.adaptiveagents.topologies.testutil.ControlledChatModel;
import io.adaptiveagents.topologies.testutil.MutableSearchTool;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Per-topology budget-cut tests (T19 degradation matrix): every topology runs with a tiny declared
 * budget ({@code budget_usd=0.001}) and a first model response whose token usage alone costs far
 * more, so the guard rejects the next node boundary. Each test asserts the terminal
 * {@code BUDGET_EXCEEDED} status, a persisted {@code partial} in the error payload, and the
 * topology-specific matrix behavior:
 * SOLO=stop, CHAIN=stop-at-stage, FAN_OUT=reduce-width/skip-remaining, SUPERVISOR=finish-with-partial.
 */
@SpringBootTest(classes = BudgetDegradationIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class BudgetDegradationIntegrationTest {

    private static final String MODEL = "gpt-4o-mini";
    private static final BigDecimal TINY_BUDGET = new BigDecimal("0.001");
    private static final int HUGE_PROMPT_TOKENS = 1_000_000;
    private static final int HUGE_COMPLETION_TOKENS = 500_000;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @SpringBootApplication(scanBasePackages = "io.adaptiveagents")
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure().dataSource(dataSource).load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        ChatModel chatModel() {
            return new ControlledChatModel();
        }

        @Bean
        @Primary
        MutableSearchTool searchTool() {
            return new MutableSearchTool();
        }
    }

    @Autowired
    ExecutionRunner runner;

    @Autowired
    RoutingService routingService;

    @Autowired
    TaskStore taskStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ControlledChatModel chatModel;

    @Autowired
    MutableSearchTool searchTool;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
        chatModel.reset();
        searchTool.reset();
    }

    @Test
    @Timeout(60)
    void soloBudgetCutStopsLoopAndKeepsTranscript() throws Exception {
        UUID taskId = UUID.randomUUID();
        route(taskId, Topology.SOLO);
        chatModel.enqueue(
                ControlledChatModel.textWithUsage("", MODEL, HUGE_PROMPT_TOKENS, HUGE_COMPLETION_TOKENS));

        ExecutionRunner.Outcome outcome = run(taskId);

        assertThat(outcome.status()).isEqualTo(TaskStatus.BUDGET_EXCEEDED);
        assertThat(status(taskId)).isEqualTo("BUDGET_EXCEEDED");

        JsonNode partial = errorPayload(taskId).path("partial");
        assertThat(partial.path("toolTranscript").isArray()).isTrue();
        assertThat(partial.path("toolTranscript").get(0).asText()).contains("iteration 0");

        assertThat(countNodeStart(taskId, "solo-agent"))
                .as("SOLO degradation = stop: the loop is cut at the next iteration boundary, "
                        + "before MAX_ITERATIONS=%s", 3)
                .isEqualTo(2);
        assertThat(countProgress(taskId, "TASK_BUDGET_EXCEEDED")).isEqualTo(1);
        assertThat(spentRows(taskId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Timeout(60)
    void chainBudgetCutStopsAtStageAndKeepsCompletedStageOutputs() throws Exception {
        UUID taskId = UUID.randomUUID();
        route(taskId, Topology.CHAIN);
        chatModel.enqueue(
                ControlledChatModel.textWithUsage("search adaptive agents", MODEL, HUGE_PROMPT_TOKENS, HUGE_COMPLETION_TOKENS));

        ExecutionRunner.Outcome outcome = run(taskId);

        assertThat(outcome.status()).isEqualTo(TaskStatus.BUDGET_EXCEEDED);
        assertThat(status(taskId)).isEqualTo("BUDGET_EXCEEDED");

        JsonNode partial = errorPayload(taskId).path("partial");
        assertThat(partial.path("stoppedAt").asText()).isEqualTo("search");
        assertThat(partial.path("plan").asText()).isEqualTo("search adaptive agents");
        assertThat(partial.path("searchResults").asInt()).isZero();

        assertThat(countNodeStart(taskId, "plan")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "search")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "summarize"))
                .as("CHAIN degradation = stop-at-stage: everything after the rejected stage is skipped")
                .isZero();
        assertThat(countNodeStart(taskId, "cite")).isZero();
        assertThat(countProgress(taskId, "TASK_BUDGET_EXCEEDED")).isEqualTo(1);
        assertThat(spentRows(taskId)).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    void fanOutBudgetCutKeepsGatheredSections() throws Exception {
        UUID taskId = UUID.randomUUID();
        route(taskId, Topology.FAN_OUT);
        chatModel.enqueue(
                ControlledChatModel.textWithUsage("Section: adaptive agents.", MODEL, HUGE_PROMPT_TOKENS, HUGE_COMPLETION_TOKENS));

        ExecutionRunner.Outcome outcome = run(taskId);

        assertThat(outcome.status()).isEqualTo(TaskStatus.BUDGET_EXCEEDED);
        assertThat(status(taskId)).isEqualTo("BUDGET_EXCEEDED");

        JsonNode partial = errorPayload(taskId).path("partial");
        int sections = partial.path("sections").size();
        assertThat(sections)
                .as("FAN_OUT degradation = reduce width: at least the first branch survives "
                        + "(its boundary precedes any spend), at most FAN_WIDTH=%s", FanOutGraphFactory.FAN_WIDTH)
                .isBetween(1, FanOutGraphFactory.FAN_WIDTH);
        assertThat(partial.path("branchStatus").size()).isEqualTo(FanOutGraphFactory.FAN_WIDTH);
        assertThat(partial.path("degraded").asBoolean()).isTrue();

        assertThat(countProgress(taskId, "TASK_BUDGET_EXCEEDED")).isEqualTo(1);
        assertThat(spentRows(taskId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Timeout(60)
    void supervisorBudgetCutFinishesWithPartial() throws Exception {
        UUID taskId = UUID.randomUUID();
        route(taskId, Topology.SUPERVISOR);
        chatModel.enqueue(
                ControlledChatModel.textWithUsage("Findings: adaptive agents adapt at runtime.", MODEL, HUGE_PROMPT_TOKENS, HUGE_COMPLETION_TOKENS));

        ExecutionRunner.Outcome outcome = run(taskId);

        assertThat(outcome.status()).isEqualTo(TaskStatus.BUDGET_EXCEEDED);
        assertThat(status(taskId)).isEqualTo("BUDGET_EXCEEDED");

        JsonNode partial = errorPayload(taskId).path("partial");
        assertThat(partial.path("research").asText()).contains("Findings");
        assertThat(partial.path("draft").asText()).isEmpty();
        assertThat(partial.path("degraded").asBoolean()).isTrue();

        assertThat(countNodeStart(taskId, "researcher")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "writer"))
                .as("SUPERVISOR degradation = finish-with-partial: the writer boundary is refused, "
                        + "the supervisor routes to finalize with the partial artifact")
                .isEqualTo(1);
        assertThat(countNodeStart(taskId, "finalize")).isEqualTo(1);
        assertThat(countProgress(taskId, "TASK_BUDGET_EXCEEDED")).isEqualTo(1);
        assertThat(spentRows(taskId)).isEqualTo(1);
    }

    private void route(UUID taskId, Topology topology) {
        TaskEnvelope envelope = new TaskEnvelope(
                taskId, "research", "research adaptive agents", 1, null, TINY_BUDGET, topology);
        routingService.route(envelope, System.currentTimeMillis());
    }

    private ExecutionRunner.Outcome run(UUID taskId) throws Exception {
        return runner.executeAsync(taskId).get(20, TimeUnit.SECONDS);
    }

    private String status(UUID taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, taskId);
    }

    private JsonNode errorPayload(UUID taskId) throws Exception {
        String error = jdbcTemplate.queryForObject("SELECT error FROM tasks WHERE task_id = ?", String.class, taskId);
        assertThat(error).as("error payload must be persisted for a budget cut").isNotNull();
        JsonNode payload = mapper.readTree(error);
        assertThat(payload.path("message").asText()).contains("budget exhausted");
        assertThat(payload.has("partial")).as("partial result must never be dropped").isTrue();
        return payload;
    }

    private int countProgress(UUID taskId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM progress_events WHERE task_id = ? AND type = ?", Integer.class, taskId, type);
        return count == null ? 0 : count;
    }

    private int countNodeStart(UUID taskId, String node) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM progress_events WHERE task_id = ? AND type = 'NODE_START' AND payload->>'node' = ?",
                Integer.class, taskId, node);
        return count == null ? 0 : count;
    }

    private int spentRows(UUID taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_cost_entries WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }
}
