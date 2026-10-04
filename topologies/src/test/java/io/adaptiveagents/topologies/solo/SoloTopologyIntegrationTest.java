package io.adaptiveagents.topologies.solo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.registry.SoloGraphFactory;
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

@SpringBootTest(classes = SoloTopologyIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.tools.throw-exception-on-error=true",
        "spring.ai.tools.limits.max-total-tool-calls=2"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class SoloTopologyIntegrationTest {

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
    SoloGraphFactory soloGraphFactory;

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
    void happyPathProducesResultAndPersistsSuccess() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        chatModel.enqueue(
                ScriptedChatModel.toolCall("web_search", "{\"query\":\"adaptive agents\"}"),
                ScriptedChatModel.text("Adaptive agents are systems that adjust their behaviour at runtime."));

        SoloState state = runGraph(taskId, prompt);

        assertThat(state.result()).isNotBlank();
        assertThat(state.degraded()).isFalse();

        taskStore.completeWithResult(taskId, json(state.result()));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT status, result FROM tasks WHERE task_id = ?", taskId);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("result")).isNotNull();
    }

    @Test
    void toolFailureDegradesAndPersistsError() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research something";
        searchTool.setThrowing(true);
        chatModel.enqueue(ScriptedChatModel.toolCall("web_search", "{\"query\":\"boom\"}"));

        SoloState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isTrue();
        assertThat(state.error()).isNotBlank();
        assertThat(state.result()).isNull();

        taskStore.completeWithError(taskId, json(state.error()));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT status, error FROM tasks WHERE task_id = ?", taskId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error")).isNotNull();
    }

    @Test
    @Timeout(30)
    void boundedLoopTerminatesWhenModelNeverFinalizes() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research forever";
        chatModel.enqueue(ScriptedChatModel.toolCall("web_search", "{\"query\":\"loop\"}"));

        SoloState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isTrue();
        assertThat(state.result()).isNull();
        assertThat(state.toolTranscript()).hasSize(SoloGraphFactory.MAX_ITERATIONS + 1);
    }

    private SoloState runGraph(UUID taskId, String prompt) {
        TaskEnvelope envelope = new TaskEnvelope(taskId, "research", prompt, 1, null, null, null);
        taskStore.submitOrGet(envelope);
        taskStore.transition(taskId, TaskStatus.ROUTED);
        taskStore.transition(taskId, TaskStatus.RUNNING);

        CompiledGraph<SoloState> graph;
        try {
            graph = soloGraphFactory.graph().compile();
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException(e);
        }
        RunnableConfig config = RunnableConfig.builder().threadId(taskId.toString()).build();
        return graph.invoke(Map.of(SoloState.PROMPT, prompt), config).orElseThrow();
    }

    private String json(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
