package io.adaptiveagents.topologies.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.registry.FanOutGraphFactory;
import io.adaptiveagents.topologies.testutil.MutableSearchTool;
import io.adaptiveagents.topologies.testutil.ScriptedChatModel;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(classes = FanOutTopologyIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class FanOutTopologyIntegrationTest {

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
            return new ScriptedChatModel();
        }

        @Bean
        @Primary
        MutableSearchTool searchTool() {
            return new MutableSearchTool();
        }
    }

    @Autowired
    FanOutGraphFactory fanOutGraphFactory;

    @Autowired
    TaskStore taskStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ScriptedChatModel chatModel;

    @Autowired
    MutableSearchTool searchTool;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        chatModel.reset();
        searchTool.reset();
    }

    @Test
    void fullFanMergesThreeSections() throws Exception {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        // Repeat-last script: one response covers all three concurrent summarize calls.
        chatModel.enqueue(ScriptedChatModel.text("Adaptive agents adapt at runtime."));

        FanOutState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isFalse();
        assertThat(state.error()).isNull();
        assertThat(state.sections()).hasSize(FanOutGraphFactory.FAN_WIDTH);

        JsonNode result = mapper.readTree(state.result());
        assertThat(result.get("sections")).hasSize(FanOutGraphFactory.FAN_WIDTH);
        assertThat(result.get("degraded").asBoolean()).isFalse();
        assertThat(result.get("branchStatus").get("branch-1").asText()).isEqualTo("ok");
        assertThat(result.get("branchStatus").get("branch-2").asText()).isEqualTo("ok");
        assertThat(result.get("branchStatus").get("branch-3").asText()).isEqualTo("ok");
    }

    @Test
    void partialFanDegradesWithSurvivorSections() throws Exception {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        // Branch 2 searches "research adaptive agents aspect 2" and fails; branches 1 and 3 survive.
        searchTool.setThrowingOnQueryContaining("aspect 2");
        chatModel.enqueue(ScriptedChatModel.text("Adaptive agents adapt at runtime."));

        FanOutState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isTrue();
        assertThat(state.error()).isNull();
        assertThat(state.sections()).hasSize(FanOutGraphFactory.FAN_WIDTH - 1);

        JsonNode result = mapper.readTree(state.result());
        assertThat(result.get("sections")).hasSize(FanOutGraphFactory.FAN_WIDTH - 1);
        assertThat(result.get("degraded").asBoolean()).isTrue();
        assertThat(result.get("branchStatus").get("branch-2").asText()).isEqualTo("failed");
        assertThat(result.get("branchStatus").get("branch-1").asText()).isEqualTo("ok");
        assertThat(result.get("branchStatus").get("branch-3").asText()).isEqualTo("ok");
    }

    @Test
    void allBranchesFailSetsError() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research nothing";
        searchTool.setThrowing(true);

        FanOutState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isTrue();
        assertThat(state.error()).isNotBlank();
        assertThat(state.sections()).isEmpty();
        assertThat(state.failures()).hasSize(FanOutGraphFactory.FAN_WIDTH);
    }

    private FanOutState runGraph(UUID taskId, String prompt) {
        taskStore.submitOrGet(new TaskEnvelope(taskId, "research", prompt, 1, null, null, null));
        CompiledGraph<FanOutState> graph;
        try {
            graph = fanOutGraphFactory.graph().compile();
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException(e);
        }
        RunnableConfig config = RunnableConfig.builder().threadId(taskId.toString()).build();
        return graph.invoke(Map.of(FanOutState.PROMPT, prompt), config).orElseThrow();
    }
}
