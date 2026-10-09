package io.adaptiveagents.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.classify.StubClassifier;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.routing.RoutingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Process-level durability proof (T17): a real app process runs a long CHAIN task, is SIGKILLed
 * mid-run, and a relaunched process carries the task to completion from Postgres checkpoints —
 * while a task whose deadline expired during the downtime is failed over to DEADLINE_EXCEEDED
 * without ever invoking the graph.
 *
 * <p>Seeding happens in the test JVM against the shared singleton Postgres BEFORE the first app
 * launch (TaskStore submit -> RoutingService CHAIN route -> RUNNING transition); the app process
 * only ever sees persisted state. The long task comes from the {@code fixture-model} profile
 * (fixed-text ChatModel, 3s per call) plus the bundled {@code adaptive-agents} search fixture, so
 * the chain spends ~3s in plan and ~3s in summarize — a wide, deterministic kill window after the
 * first two checkpoints.</p>
 */
@Tag("e2e")
class ResumeAfterKillE2eTest {

    private static final long MODEL_DELAY_MS = 3000;
    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 200;

    private static final Map<String, String> APP_ENV = Map.of(
            "SPRING_PROFILES_ACTIVE", "harness,fixture-model,stub-classifier",
            "SPRING_DATASOURCE_URL", SingletonPostgres.getJdbcUrl(),
            "SPRING_DATASOURCE_USERNAME", SingletonPostgres.getUsername(),
            "SPRING_DATASOURCE_PASSWORD", SingletonPostgres.getPassword());

    private static final String[] APP_ARGS = {
            "--spring.main.web-application-type=none",
            "--spring.ai.model.chat=none",
            "--runtime.fixture-model.delay-ms=" + MODEL_DELAY_MS
    };

    private static TaskStore taskStore;
    private static RoutingService routingService;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareDatabaseAndStores() {
        Flyway.configure()
                .dataSource(SingletonPostgres.getJdbcUrl(), SingletonPostgres.getUsername(), SingletonPostgres.getPassword())
                .load()
                .migrate();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(SingletonPostgres.getJdbcUrl());
        dataSource.setUsername(SingletonPostgres.getUsername());
        dataSource.setPassword(SingletonPostgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        taskStore = new TaskStore(jdbc, new ObjectMapper(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        routingService = new RoutingService(taskStore, new StubClassifier());
    }

    @BeforeEach
    void resetTaskState() {
        jdbc.execute("TRUNCATE tasks CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    @Test
    @Timeout(300)
    void killMidRunThenRelaunchCompletesFromCheckpoints() throws Exception {
        UUID taskId = UUID.randomUUID();
        seedRunningChainTask(taskId, null);

        SpawnedApp first = new SpawnedApp(fatJar(), Paths.get("target", "spawned", "resume-launch-1.log"), APP_ENV, APP_ARGS);
        SpawnedApp second = new SpawnedApp(fatJar(), Paths.get("target", "spawned", "resume-launch-2.log"), APP_ENV, APP_ARGS);
        try {
            first.start();
            first.awaitHealthy(60);

            List<Integer> checkpointSamples = new ArrayList<>();
            // Kill trigger = "summarize started", not "checkpoint count >= 2": the saver also
            // writes an initial-state checkpoint, so a raw count of 2 is reachable before the
            // search checkpoint exists. And the pre-kill count is sampled AFTER the condition is
            // observed: with summarize started, plan+search checkpoints are committed and the
            // next write is ~3s away (summarize's model delay) — a stable snapshot to kill against.
            pollUntil("summarize node started (plan+search checkpointed, summarize in flight)",
                    () -> nodeStartCount(taskId, "summarize") >= 1);
            checkpointSamples.add(checkpointCount(taskId));
            int beforeKill = checkpointSamples.getLast();

            first.stopSigKill();

            assertThat(status(taskId)).isEqualTo("RUNNING");
            assertThat(checkpointCount(taskId)).isEqualTo(beforeKill);

            second.start();
            second.awaitHealthy(60);

            pollUntil("task SUCCEEDED after relaunch", () -> {
                boolean done = TaskStatus.SUCCEEDED.name().equals(status(taskId));
                checkpointSamples.add(checkpointCount(taskId));
                return done;
            });

            assertThat(result(taskId)).isNotBlank();

            for (int i = 1; i < checkpointSamples.size(); i++) {
                assertThat(checkpointSamples.get(i)).isGreaterThanOrEqualTo(checkpointSamples.get(i - 1));
            }
            assertThat(checkpointSamples.getLast()).isGreaterThan(beforeKill);

            assertThat(nodeStartCount(taskId, "plan")).isEqualTo(1);
            assertThat(nodeStartCount(taskId, "search")).isEqualTo(1);
            assertThat(nodeStartCount(taskId, "cite")).isEqualTo(1);
            assertThat(nodeStartCount(taskId, "summarize")).isLessThanOrEqualTo(2);
            assertThat(progressCount(taskId, "TASK_COMPLETED")).isEqualTo(1);
        } finally {
            first.stopSigKill();
            second.stopSigKill();
        }
    }

    @Test
    @Timeout(180)
    void deadlineExpiredWhileDownIsNotResumed() throws Exception {
        UUID taskId = UUID.randomUUID();
        seedRunningChainTask(taskId, System.currentTimeMillis() - Duration.ofHours(1).toMillis());

        SpawnedApp app = new SpawnedApp(fatJar(), Paths.get("target", "spawned", "resume-expired.log"), APP_ENV, APP_ARGS);
        try {
            app.start();
            app.awaitHealthy(60);

            pollUntil("expired task failed over to DEADLINE_EXCEEDED",
                    () -> TaskStatus.DEADLINE_EXCEEDED.name().equals(status(taskId)));

            assertThat(error(taskId)).isNotBlank();
            assertThat(progressCount(taskId, "TASK_DEADLINE_EXCEEDED")).isEqualTo(1);
            assertThat(totalNodeStarts(taskId)).isZero();
            assertThat(checkpointCount(taskId)).isZero();
        } finally {
            app.stopSigKill();
        }
    }

    private void seedRunningChainTask(UUID taskId, Long deadlineMs) {
        TaskEnvelope envelope = new TaskEnvelope(
                taskId, "research", "research adaptive agents", 1, deadlineMs, null, Topology.CHAIN);
        routingService.route(envelope, System.currentTimeMillis());
        taskStore.transition(taskId, TaskStatus.RUNNING);
    }

    private static Path fatJar() throws Exception {
        Path targetDir = Paths.get("target");
        if (!Files.exists(targetDir)) {
            fail("App target/ missing — run 'mvn package' before e2e tests.");
        }
        try (var stream = Files.list(targetDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")
                            && !p.toString().endsWith("-original.jar")
                            && !p.toString().endsWith("-sources.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("App fat jar not found in target/"));
        }
    }

    private interface PollCondition {
        boolean satisfied();
    }

    private static void pollUntil(String expectation, PollCondition probe) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (probe.satisfied()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Timed out (120s) waiting for: " + expectation);
    }

    private static String queryString(String sql, Object... params) {
        try (Connection conn = DriverManager.getConnection(SingletonPostgres.getJdbcUrl(),
                SingletonPostgres.getUsername(), SingletonPostgres.getPassword());
             PreparedStatement stmt = prepare(conn, sql, params);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private static int queryCount(String sql, Object... params) {
        try (Connection conn = DriverManager.getConnection(SingletonPostgres.getJdbcUrl(),
                SingletonPostgres.getUsername(), SingletonPostgres.getPassword());
             PreparedStatement stmt = prepare(conn, sql, params);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private static PreparedStatement prepare(Connection conn, String sql, Object... params) throws Exception {
        PreparedStatement stmt = conn.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
        return stmt;
    }

    private static String status(UUID taskId) {
        return queryString("SELECT status FROM tasks WHERE task_id = ?", taskId);
    }

    private static String result(UUID taskId) {
        return queryString("SELECT result FROM tasks WHERE task_id = ?", taskId);
    }

    private static String error(UUID taskId) {
        return queryString("SELECT error FROM tasks WHERE task_id = ?", taskId);
    }

    private static int checkpointCount(UUID taskId) {
        try {
            return queryCount("SELECT COUNT(*) FROM lg4jcheckpoint c "
                    + "JOIN lg4jthread t ON t.thread_id = c.thread_id WHERE t.thread_name = ?", taskId.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int progressCount(UUID taskId, String type) {
        return queryCount("SELECT COUNT(*) FROM progress_events WHERE task_id = ? AND type = ?", taskId, type);
    }

    private static int nodeStartCount(UUID taskId, String node) {
        return queryCount("SELECT COUNT(*) FROM progress_events WHERE task_id = ?"
                + " AND type = 'NODE_START' AND payload->>'node' = ?", taskId, node);
    }

    private static int totalNodeStarts(UUID taskId) {
        return queryCount("SELECT COUNT(*) FROM progress_events WHERE task_id = ? AND type = 'NODE_START'", taskId);
    }
}
