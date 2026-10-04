package io.adaptiveagents.topologies.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.registry.TopologyRegistry;
import io.adaptiveagents.topologies.testutil.ControlledChatModel;
import io.adaptiveagents.topologies.testutil.MutableSearchTool;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

@SpringBootTest(classes = ExecutionRunnerIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class ExecutionRunnerIntegrationTest {

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
    TopologyRegistry topologyRegistry;

    @Autowired
    TaskStore taskStore;

    @Autowired
    ProgressService progressService;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ControlledChatModel chatModel;

    @Autowired
    MutableSearchTool searchTool;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        // Saver tables are lazily created by PostgresSaver on first compile; drop-if-exists so this
        // is idempotent on a fresh container. They are recreated by the saver's createTables(true).
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
        chatModel.reset();
        searchTool.reset();
    }

    @Test
    @Timeout(60)
    void durableChainRunPersistsCheckpointsAndCompletes() throws Exception {
        UUID taskId = UUID.randomUUID();
        routeChain(taskId, null);
        CountDownLatch summarizeGate = new CountDownLatch(1);
        chatModel.blockCall(2, summarizeGate);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents\nsearch runtime"),
                ControlledChatModel.text("Adaptive agents adjust behaviour at runtime."));

        CompletableFuture<ExecutionRunner.Outcome> run = runner.executeAsync(taskId);

        awaitCheckpointCount(taskId, 1);
        assertThat(status(taskId)).isEqualTo("RUNNING");

        summarizeGate.countDown();
        ExecutionRunner.Outcome outcome = run.get(20, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(status(taskId)).isEqualTo("SUCCEEDED");
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT result FROM tasks WHERE task_id = ?", taskId);
        assertThat(row.get("result")).isNotNull();
        assertThat(countNodeStart(taskId, "plan")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "search")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "summarize")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "cite")).isEqualTo(1);
        assertThat(countProgress(taskId, "TASK_COMPLETED")).isEqualTo(1);
        assertThat(checkpointCount(taskId)).isGreaterThanOrEqualTo(4);
    }

    @Test
    @Timeout(60)
    void deadlineExceededMidRunCompletesRunFuture() throws Exception {
        UUID taskId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 150;
        routeChain(taskId, deadline);
        chatModel.setDelayMs(300);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents"),
                ControlledChatModel.text("summary"));

        ExecutionRunner.Outcome outcome = runner.executeAsync(taskId).get(20, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.DEADLINE_EXCEEDED);
        assertThat(status(taskId)).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(countProgress(taskId, "TASK_DEADLINE_EXCEEDED")).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    void resumeAfterAbandonSkipsCompletedNodes() throws Exception {
        UUID taskId = UUID.randomUUID();
        routeChain(taskId, null);
        CountDownLatch summarizeGate = new CountDownLatch(1);
        chatModel.blockCall(2, summarizeGate);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents\nsearch runtime"),
                ControlledChatModel.text("Adaptive agents adapt at runtime."));

        CompletableFuture<ExecutionRunner.Outcome> abandoned = runner.executeAsync(taskId);

        awaitCheckpointCount(taskId, 2);
        assertThat(status(taskId)).isEqualTo("RUNNING");

        // Simulated process kill: the in-flight run is abandoned mid-summarize (its latch is never
        // released), leaving the task RUNNING with plan+search checkpointed. Resume from a NEW
        // runner instance — everything needed to continue lives in Postgres, not in memory.
        ExecutionRunner secondRunner =
                new ExecutionRunner(topologyRegistry, taskStore, progressService, dataSource);
        ExecutionRunner.Outcome outcome = secondRunner.resume(taskId);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(status(taskId)).isEqualTo("SUCCEEDED");
        assertThat(countNodeStart(taskId, "plan")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "search")).isEqualTo(1);
        assertThat(countNodeStart(taskId, "summarize")).isEqualTo(2);
        assertThat(countNodeStart(taskId, "cite")).isEqualTo(1);
        assertThat(countProgress(taskId, "TASK_COMPLETED")).isEqualTo(1);
    }

    private void routeChain(UUID taskId, Long deadlineMs) {
        TaskEnvelope envelope = new TaskEnvelope(taskId, "research", "research adaptive agents", 1, deadlineMs, null, null);
        taskStore.submitOrGet(envelope);
        taskStore.recordDecision(taskId, Topology.CHAIN, "{\"source\":\"OVERRIDE\"}");
        taskStore.transition(taskId, TaskStatus.ROUTED);
    }

    private String status(UUID taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, taskId);
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

    private int checkpointCount(UUID taskId) {
        // The saver stores the task id as thread_name; LG4JCheckpoint.thread_id is a saver-generated
        // UUID referencing LG4JThread.thread_id. Join through LG4JThread to count by task.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lg4jcheckpoint c "
                        + "JOIN lg4jthread t ON t.thread_id = c.thread_id "
                        + "WHERE t.thread_name = ?",
                Integer.class, taskId.toString());
        return count == null ? 0 : count;
    }

    private void awaitCheckpointCount(UUID taskId, int min) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (checkpointCount(taskId) >= min) {
                    return;
                }
            } catch (Exception ignored) {
                // saver tables not created yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("checkpoint count never reached " + min
                + " (status=" + safeStatus(taskId) + ", nodeStarts=" + safeNodeStarts(taskId) + ")");
    }

    private String safeStatus(UUID taskId) {
        try {
            return status(taskId);
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
    }

    private String safeNodeStarts(UUID taskId) {
        try {
            return jdbcTemplate.queryForList(
                            "SELECT payload->>'node' AS node FROM progress_events WHERE task_id = ? AND type = 'NODE_START'", String.class, taskId)
                    .toString();
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
    }
}
