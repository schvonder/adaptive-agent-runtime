package io.adaptiveagents.topologies.sweep;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import io.adaptiveagents.topologies.testutil.ControlledChatModel;
import io.adaptiveagents.topologies.testutil.MutableSearchTool;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T20 acceptance: the runtime deadline sweep leaves no zombies. Five scenarios against the real
 * Postgres-backed context with the sweep ticking every 100ms: the mid-node hang only the sweep
 * can stop, the plan's 200ms/300ms boundary acceptance, the sweep ignoring undedlined RUNNING
 * tasks, the sweep directly finalizing an expired RUNNING row with no local run, and the
 * happy path inside its deadline.
 */
@SpringBootTest(classes = DeadlineSweepIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none",
        "runtime.sweep.interval-ms=100"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class DeadlineSweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @SpringBootApplication(scanBasePackages = "io.adaptiveagents")
    @EnableScheduling
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
    TaskStore taskStore;

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

    /**
     * THE core T20 scenario: a model call that never returns mid-node. Boundary guards can never
     * fire (no node boundary is reached), so only the sweep can stop it: cancel interrupts the
     * hung worker, the runner's cancellation branch finalizes DEADLINE_EXCEEDED, the run future
     * completes (no zombie future), and the in-flight registry drains (no zombie run).
     */
    @Test
    @Timeout(60)
    void sweepCancelsMidNodeHang() throws Exception {
        UUID taskId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 200;
        routeChain(taskId, deadline);
        CountDownLatch neverReleased = new CountDownLatch(1);
        chatModel.blockCall(2, neverReleased);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents"),
                ControlledChatModel.text("summary"));

        CompletableFuture<ExecutionRunner.Outcome> run = runner.executeAsync(taskId);

        awaitStatus(taskId, TaskStatus.DEADLINE_EXCEEDED, 10_000);
        assertThat(countProgress(taskId, "TASK_DEADLINE_EXCEEDED")).isGreaterThanOrEqualTo(1);
        awaitInFlightCount(0, 5_000);
        ExecutionRunner.Outcome outcome = run.get(20, TimeUnit.SECONDS);
        assertThat(outcome.status()).isEqualTo(TaskStatus.DEADLINE_EXCEEDED);
    }

    /**
     * The plan's exact acceptance: a 200ms deadline against a 4-node CHAIN whose every model call
     * takes 300ms. Either exit path is valid — the boundary guard fires at the first boundary
     * past the deadline, or the sweep cancels the in-flight call — and the registry must be empty
     * within 5s of the terminal status.
     */
    @Test
    @Timeout(60)
    void planAcceptanceDeadlineWithin5s() throws Exception {
        UUID taskId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 200;
        routeChain(taskId, deadline);
        chatModel.setDelayMs(300);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents"),
                ControlledChatModel.text("summary"));

        CompletableFuture<ExecutionRunner.Outcome> run = runner.executeAsync(taskId);

        awaitStatus(taskId, TaskStatus.DEADLINE_EXCEEDED, 10_000);
        awaitInFlightCount(0, 5_000);
        ExecutionRunner.Outcome outcome = run.get(20, TimeUnit.SECONDS);
        assertThat(outcome.status()).isEqualTo(TaskStatus.DEADLINE_EXCEEDED);
        assertThat(countProgress(taskId, "TASK_DEADLINE_EXCEEDED")).isGreaterThanOrEqualTo(1);
    }

    /** A RUNNING task without a deadline is never sweep territory: parked forever, stays RUNNING. */
    @Test
    @Timeout(60)
    void sweepLeavesUndeadlinedRunningTasks() throws Exception {
        UUID taskId = UUID.randomUUID();
        routeChain(taskId, null);
        CountDownLatch neverReleased = new CountDownLatch(1);
        chatModel.blockCall(2, neverReleased);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents"),
                ControlledChatModel.text("summary"));

        CompletableFuture<ExecutionRunner.Outcome> run = runner.executeAsync(taskId);

        Thread.sleep(300);
        assertThat(status(taskId)).isEqualTo("RUNNING");
        assertThat(runner.inFlightCount()).isEqualTo(1);

        // Cleanup: cancel so the parked model call cannot leak into a later test's script.
        runner.cancel(taskId);
    }

    /**
     * A RUNNING row with an expired deadline and no local run (seeded RUNNING, never executed —
     * the abandoned/foreign shape): the sweep finalizes it directly, graph never invoked, event
     * reason records the no-local-run provenance.
     */
    @Test
    @Timeout(60)
    void sweepFinalizesRunningRowWithoutLocalRun() throws Exception {
        UUID taskId = UUID.randomUUID();
        routeChain(taskId, System.currentTimeMillis() - 1_000);
        taskStore.transition(taskId, TaskStatus.RUNNING);

        awaitStatus(taskId, TaskStatus.DEADLINE_EXCEEDED, 10_000);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM progress_events WHERE task_id = ?"
                        + " AND type = 'TASK_DEADLINE_EXCEEDED' AND payload->>'reason' LIKE '%no local run%'",
                Integer.class, taskId);
        assertThat(events).isEqualTo(1);
        assertThat(runner.inFlightCount()).isZero();
    }

    /** Happy path: a task comfortably inside its deadline is never touched by the sweep. */
    @Test
    @Timeout(60)
    void happyWithinDeadline() throws Exception {
        UUID taskId = UUID.randomUUID();
        routeChain(taskId, System.currentTimeMillis() + 10_000);
        chatModel.enqueue(
                ControlledChatModel.text("search adaptive agents\nsearch runtime"),
                ControlledChatModel.text("Adaptive agents adapt at runtime."));

        ExecutionRunner.Outcome outcome = runner.executeAsync(taskId).get(20, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(status(taskId)).isEqualTo("SUCCEEDED");
        assertThat(runner.inFlightCount()).isZero();
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

    private void awaitStatus(UUID taskId, TaskStatus expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = status(taskId);
            if (expected.name().equals(last)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task never reached " + expected + " within " + timeoutMs + "ms (last=" + last + ")");
    }

    private void awaitInFlightCount(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (runner.inFlightCount() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(runner.inFlightCount()).isEqualTo(expected);
    }
}
