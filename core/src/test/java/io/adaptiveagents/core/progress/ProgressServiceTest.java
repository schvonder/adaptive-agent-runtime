package io.adaptiveagents.core.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.store.TaskStore;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class ProgressServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static HikariDataSource dataSource;
    static ProgressService progressService;
    static TaskStore taskStore;
    static JdbcTemplate jdbcTemplate;
    static ObjectMapper mapper = new ObjectMapper();

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
        taskStore = new TaskStore(jdbcTemplate, mapper,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        progressService = new ProgressService(jdbcTemplate, mapper);
    }

    @AfterAll
    static void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE progress_events CASCADE");
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
    }

    private UUID createRandomTask() {
        TaskEnvelope env = new TaskEnvelope(UUID.randomUUID(), "research", "Prompt", 1, null, null, null);
        taskStore.submitOrGet(env);
        return env.taskId();
    }

    @Test
    void orderedReplay() {
        UUID taskId = createRandomTask();
        long seq1 = progressService.publish(taskId, "NODE_START", null);
        long seq2 = progressService.publish(taskId, "NODE_RUNNING", null);
        long seq3 = progressService.publish(taskId, "NODE_END", null);

        List<ProgressEvent> all = progressService.sinceSeq(taskId, 0);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).seq()).isEqualTo(seq1);
        assertThat(all.get(1).seq()).isEqualTo(seq2);
        assertThat(all.get(2).seq()).isEqualTo(seq3);

        List<ProgressEvent> subset = progressService.sinceSeq(taskId, seq2);
        assertThat(subset).hasSize(1);
        assertThat(subset.get(0).seq()).isEqualTo(seq3);
    }

    @Test
    void monotonicSeq() {
        UUID task1 = createRandomTask();
        UUID task2 = createRandomTask();

        long s1 = progressService.publish(task1, "T1_1", null);
        long s2 = progressService.publish(task2, "T2_1", null);
        long s3 = progressService.publish(task1, "T1_2", null);
        long s4 = progressService.publish(task2, "T2_2", null);
        long s5 = progressService.publish(task1, "T1_3", null);

        assertThat(s2).isGreaterThan(s1);
        assertThat(s3).isGreaterThan(s2);
        assertThat(s4).isGreaterThan(s3);
        assertThat(s5).isGreaterThan(s4);

        List<ProgressEvent> t1Events = progressService.sinceSeq(task1, 0);
        assertThat(t1Events).hasSize(3);
        assertThat(t1Events.get(0).seq()).isLessThan(t1Events.get(1).seq());
        assertThat(t1Events.get(1).seq()).isLessThan(t1Events.get(2).seq());
    }

    @Test
    void unknownTaskEmpty() {
        List<ProgressEvent> events = progressService.sinceSeq(UUID.randomUUID(), 0);
        assertThat(events).isEmpty();
    }

    @Test
    void payloadRoundtrip() throws Exception {
        UUID taskId = createRandomTask();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("status", "ok");
        ObjectNode details = payload.putObject("details");
        details.put("nodesVisited", 42);

        progressService.publish(taskId, "COMPLETED", payload);

        List<ProgressEvent> events = progressService.sinceSeq(taskId, 0);
        assertThat(events).hasSize(1);
        
        String savedPayload = events.get(0).payload();
        assertThat(savedPayload).isNotNull();
        
        ObjectNode parsed = (ObjectNode) mapper.readTree(savedPayload);
        assertThat(parsed).isEqualTo(payload);
    }
}
