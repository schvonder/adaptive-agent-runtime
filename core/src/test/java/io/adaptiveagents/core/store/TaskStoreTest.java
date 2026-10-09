package io.adaptiveagents.core.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
public class TaskStoreTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static HikariDataSource dataSource;
    static TaskStore taskStore;
    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);

        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        taskStore = new TaskStore(jdbcTemplate, new ObjectMapper(), transactionTemplate());
    }

    private static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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

    private TaskEnvelope createEnvelope() {
        return new TaskEnvelope(UUID.randomUUID(), "research", "Test prompt", 1, null, null, null);
    }

    @Test
    void fullLifecycle() {
        TaskEnvelope env = createEnvelope();
        TaskRecord record = taskStore.submitOrGet(env).record();
        
        assertThat(record.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(record.version()).isEqualTo(0);
        
        record = taskStore.transition(env.taskId(), TaskStatus.ROUTED);
        assertThat(record.status()).isEqualTo(TaskStatus.ROUTED);
        assertThat(record.version()).isEqualTo(1);
        
        record = taskStore.transition(env.taskId(), TaskStatus.RUNNING);
        assertThat(record.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(record.version()).isEqualTo(2);
        
        record = taskStore.transition(env.taskId(), TaskStatus.SUCCEEDED);
        assertThat(record.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(record.version()).isEqualTo(3);
    }

    @Test
    void duplicateInsertResolvesToExisting() {
        TaskEnvelope env = createEnvelope();
        SubmitResult r1 = taskStore.submitOrGet(env);
        SubmitResult r2 = taskStore.submitOrGet(env);

        assertThat(r1.created()).isTrue();
        assertThat(r2.created()).isFalse();
        assertThat(r1.record().taskId()).isEqualTo(r2.record().taskId());
        assertThat(r1.record().status()).isEqualTo(r2.record().status());
        assertThat(r1.record().version()).isEqualTo(r2.record().version());
    }

    @Test
    void concurrentDuplicateSubmissionsYieldExactlyOneCreation() throws Exception {
        TaskEnvelope env = createEnvelope();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<SubmitResult>> submissions = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                submissions.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return taskStore.submitOrGet(env);
                }));
            }

            int created = 0;
            for (Future<SubmitResult> submission : submissions) {
                SubmitResult result = submission.get(20, TimeUnit.SECONDS);
                assertThat(result.record().taskId()).isEqualTo(env.taskId());
                assertThat(result.record().status()).isEqualTo(TaskStatus.SUBMITTED);
                assertThat(result.record().version()).isZero();
                if (result.created()) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tasks WHERE task_id = ?", Integer.class, env.taskId());
            assertThat(rows).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void illegalTransitionRejected() {
        TaskEnvelope env = createEnvelope();
        taskStore.submitOrGet(env);
        taskStore.transition(env.taskId(), TaskStatus.ROUTED);
        taskStore.transition(env.taskId(), TaskStatus.RUNNING);
        taskStore.transition(env.taskId(), TaskStatus.SUCCEEDED);
        
        // SUCCEEDED -> RUNNING
        assertThatThrownBy(() -> taskStore.transition(env.taskId(), TaskStatus.RUNNING))
                .isInstanceOf(IllegalTaskStateException.class)
                .hasMessageContaining("Cannot transition");

        // SUCCEEDED -> FAILED
        assertThatThrownBy(() -> taskStore.transition(env.taskId(), TaskStatus.FAILED))
                .isInstanceOf(IllegalTaskStateException.class)
                .hasMessageContaining("Cannot transition");
    }

    @Test
    void optimisticConcurrency() throws Exception {
        TaskEnvelope env = createEnvelope();
        taskStore.submitOrGet(env);
        
        java.util.concurrent.CountDownLatch latch1 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);
        
        TaskStore slowStore = new TaskStore(jdbcTemplate, new ObjectMapper(), transactionTemplate()) {
            @Override
            public Optional<TaskRecord> find(UUID taskId) {
                Optional<TaskRecord> r = super.find(taskId);
                try {
                    latch1.countDown();
                    latch2.await();
                } catch (InterruptedException e) {}
                return r;
            }
        };

        java.util.concurrent.Future<?> f = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
            slowStore.transition(env.taskId(), TaskStatus.ROUTED);
        });

        latch1.await();
        taskStore.transition(env.taskId(), TaskStatus.ROUTED);
        latch2.countDown();
        
        assertThatThrownBy(f::get)
                .hasCauseInstanceOf(IllegalTaskStateException.class)
                .hasMessageContaining("Optimistic concurrency failure");
                
        TaskRecord after = taskStore.find(env.taskId()).get();
        assertThat(after.status()).isEqualTo(TaskStatus.ROUTED);
        assertThat(after.version()).isEqualTo(1);
    }

    @Test
    void unknownTask() {
        Optional<TaskRecord> record = taskStore.find(UUID.randomUUID());
        assertThat(record).isEmpty();
    }
}
