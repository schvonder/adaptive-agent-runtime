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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

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
        taskStore = new TaskStore(jdbcTemplate, new ObjectMapper());
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
        TaskRecord record = taskStore.submitOrGet(env);
        
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
        TaskRecord r1 = taskStore.submitOrGet(env);
        TaskRecord r2 = taskStore.submitOrGet(env);
        
        assertThat(r1.taskId()).isEqualTo(r2.taskId());
        assertThat(r1.status()).isEqualTo(r2.status());
        assertThat(r1.version()).isEqualTo(r2.version());
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
        TaskRecord record = taskStore.submitOrGet(env);
        
        java.util.concurrent.CountDownLatch latch1 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);
        
        TaskStore slowStore = new TaskStore(jdbcTemplate, new ObjectMapper()) {
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
