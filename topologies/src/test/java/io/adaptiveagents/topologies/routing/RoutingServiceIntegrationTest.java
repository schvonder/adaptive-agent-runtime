package io.adaptiveagents.topologies.routing;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = RoutingServiceIntegrationTest.Config.class)
@ActiveProfiles("stub-classifier")
@Testcontainers
class RoutingServiceIntegrationTest {

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
    }

    @Autowired
    RoutingService routingService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
    }

    private TaskEnvelope envelope(int breadth) {
        return new TaskEnvelope(UUID.randomUUID(), "research", "Investigate the topic thoroughly", breadth, null, null, null);
    }

    @Test
    void deterministicTopologyForAmbiguousEnvelopes() {
        Topology expected = null;
        for (int i = 0; i < 10; i++) {
            RoutingDecision decision = routingService.route(envelope(2), 1_000_000L);
            if (expected == null) {
                expected = decision.topology();
            }
            assertThat(decision.topology()).isEqualTo(expected);
            assertThat(decision.source()).isEqualTo(DecisionSource.LLM);
        }
        assertThat(expected).isEqualTo(Topology.CHAIN);
    }

    @Test
    void forcedTopologyOverridesClassifier() {
        TaskEnvelope env = new TaskEnvelope(UUID.randomUUID(), "research", "Complex task", 5, null, null, Topology.SOLO);

        RoutingDecision decision = routingService.route(env, 1_000_000L);

        assertThat(decision.topology()).isEqualTo(Topology.SOLO);
        assertThat(decision.source()).isEqualTo(DecisionSource.OVERRIDE);
    }

    @Test
    void decisionPersistedToTaskRow() {
        TaskEnvelope env = envelope(1);
        routingService.route(env, 1_000_000L);

        String topology = jdbcTemplate.queryForObject(
                "SELECT topology FROM tasks WHERE task_id = ?", String.class, env.taskId());
        String decision = jdbcTemplate.queryForObject(
                "SELECT decision::text FROM tasks WHERE task_id = ?", String.class, env.taskId());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tasks WHERE task_id = ?", String.class, env.taskId());

        assertThat(topology).isEqualTo(Topology.SOLO.name());
        assertThat(status).isEqualTo("ROUTED");
        assertThat(decision).isNotNull().contains("source").contains("RULES");
    }

    @Test
    void invalidForcedTopologyFailsDeserialization() {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "research",
          "prompt": "Test prompt",
          "forced_topology": "TEAM"
        }
        """;

        assertThatThrownBy(() -> mapper.readValue(json, TaskEnvelope.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("TEAM");
    }
}
