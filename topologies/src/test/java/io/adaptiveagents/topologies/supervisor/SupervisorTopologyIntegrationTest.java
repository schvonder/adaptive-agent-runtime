package io.adaptiveagents.topologies.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.registry.SupervisorGraphFactory;
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

@SpringBootTest(classes = SupervisorTopologyIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class SupervisorTopologyIntegrationTest {

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
    SupervisorGraphFactory supervisorGraphFactory;

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
    void acceptWithinCapFinishesCleanly() throws Exception {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        chatModel.enqueue(
                ScriptedChatModel.text("Findings: adaptive agents adapt at runtime."),
                ScriptedChatModel.text("Draft: adaptive agents adapt at runtime."),
                ScriptedChatModel.text("ACCEPT: the draft is ready."));

        SupervisorState state = runGraph(taskId, prompt);

        assertThat(state.finished()).isTrue();
        assertThat(state.accepted()).isTrue();
        assertThat(state.degraded()).isFalse();
        assertThat(state.error()).isNull();
        assertThat(state.criticRounds()).isEqualTo(1);

        JsonNode result = mapper.readTree(state.result());
        assertThat(result.get("criticRounds").asInt()).isEqualTo(1);
        assertThat(result.get("degraded").asBoolean()).isFalse();
        assertThat(result.get("draft").asText()).contains("adaptive agents");
        assertThat(result.get("finalCritique").asText()).startsWith("ACCEPT");
    }

    @Test
    void neverSatisfiedForcedFinishDegrades() throws Exception {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        chatModel.enqueue(
                ScriptedChatModel.text("Findings: adaptive agents adapt at runtime."),
                ScriptedChatModel.text("Draft v1: short."),
                ScriptedChatModel.text("REVISE: needs more detail."),
                ScriptedChatModel.text("Draft v2: still short."),
                ScriptedChatModel.text("REVISE: still not enough."));

        SupervisorState state = runGraph(taskId, prompt);

        assertThat(state.finished()).isTrue();
        assertThat(state.accepted()).isFalse();
        assertThat(state.degraded()).isTrue();
        assertThat(state.criticRounds()).isEqualTo(SupervisorGraphFactory.MAX_CRITIC_ROUNDS);

        JsonNode result = mapper.readTree(state.result());
        assertThat(result.get("criticRounds").asInt()).isEqualTo(SupervisorGraphFactory.MAX_CRITIC_ROUNDS);
        assertThat(result.get("degraded").asBoolean()).isTrue();
        assertThat(result.get("finalCritique").asText()).startsWith("REVISE");
    }

    @Test
    void boundedTrajectoryNeverExceedsCap() throws Exception {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        // Script enough draft/critique pairs for three rounds; a bounded loop must stop after two.
        chatModel.enqueue(
                ScriptedChatModel.text("Findings: adaptive agents adapt at runtime."),
                ScriptedChatModel.text("Draft v1."),
                ScriptedChatModel.text("REVISE: round 1."),
                ScriptedChatModel.text("Draft v2."),
                ScriptedChatModel.text("REVISE: round 2."),
                ScriptedChatModel.text("Draft v3 (must never be written)."),
                ScriptedChatModel.text("REVISE: round 3 (must never be requested)."));

        SupervisorState state = runGraph(taskId, prompt);

        assertThat(state.finished()).isTrue();
        assertThat(state.criticRounds()).isEqualTo(SupervisorGraphFactory.MAX_CRITIC_ROUNDS);
        // The third revision round is structurally unreachable.
        assertThat(state.draft()).contains("Draft v2");
        assertThat(state.draft()).doesNotContain("Draft v3");
    }

    @Test
    void malformedCriticOutputTreatedAsRevise() throws Exception {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        chatModel.enqueue(
                ScriptedChatModel.text("Findings: adaptive agents adapt at runtime."),
                ScriptedChatModel.text("Draft v1."),
                ScriptedChatModel.text("this verdict is neither accept nor revise"),
                ScriptedChatModel.text("Draft v2."),
                ScriptedChatModel.text("still an unrecognizable verdict"));

        SupervisorState state = runGraph(taskId, prompt);

        assertThat(state.finished()).isTrue();
        assertThat(state.accepted()).isFalse();
        assertThat(state.degraded()).isTrue();
        assertThat(state.criticRounds()).isEqualTo(SupervisorGraphFactory.MAX_CRITIC_ROUNDS);
    }

    private SupervisorState runGraph(UUID taskId, String prompt) {
        taskStore.submitOrGet(new TaskEnvelope(taskId, "research", prompt, 1, null, null, null));
        CompiledGraph<SupervisorState> graph;
        try {
            graph = supervisorGraphFactory.graph().compile();
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException(e);
        }
        RunnableConfig config = RunnableConfig.builder().threadId(taskId.toString()).build();
        return graph.invoke(Map.of(SupervisorState.PROMPT, prompt), config).orElseThrow();
    }
}
