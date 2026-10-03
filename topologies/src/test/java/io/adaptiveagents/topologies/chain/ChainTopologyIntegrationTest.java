package io.adaptiveagents.topologies.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.topologies.registry.ChainGraphFactory;
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

@SpringBootTest(classes = ChainTopologyIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class ChainTopologyIntegrationTest {

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
    ChainGraphFactory chainGraphFactory;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ScriptedChatModel chatModel;

    @Autowired
    MutableSearchTool searchTool;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        chatModel.reset();
        searchTool.reset();
    }

    @Test
    void happyPathRunsAllFourStages() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research adaptive agents";
        chatModel.enqueue(
                ScriptedChatModel.text("search adaptive agents\nsearch runtime adaptation"),
                ScriptedChatModel.text("Adaptive agents adjust their behaviour at runtime."));

        ChainState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isFalse();
        assertThat(state.error()).isNull();
        assertThat(state.plan()).isNotBlank();
        assertThat(state.summary()).isEqualTo("Adaptive agents adjust their behaviour at runtime.");
        assertThat(state.citations()).isNotEmpty();
        assertThat(state.result()).contains("Adaptive agents adjust their behaviour at runtime.");
        assertThat(state.result()).contains("citations");
    }

    @Test
    void emptyFixtureGraceful() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research nothing";
        searchTool.setReturnEmpty(true);
        chatModel.enqueue(ScriptedChatModel.text("search nothing"));

        ChainState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isFalse();
        assertThat(state.summary()).isEqualTo("No sources found.");
        assertThat(state.citations()).isEmpty();
        assertThat(state.result()).contains("No sources found.");
    }

    @Test
    void summarizeModelErrorDegrades() {
        UUID taskId = UUID.randomUUID();
        String prompt = "research something";
        chatModel.enqueue(ScriptedChatModel.text("search something"));
        chatModel.enqueueThrow(new RuntimeException("model unavailable"));

        ChainState state = runGraph(taskId, prompt);

        assertThat(state.degraded()).isTrue();
        assertThat(state.error()).contains("summarize");
        assertThat(state.stage()).isEqualTo("summarize");
        assertThat(state.result()).isNull();
    }

    private ChainState runGraph(UUID taskId, String prompt) {
        CompiledGraph<ChainState> graph = chainGraphFactory.build();
        RunnableConfig config = RunnableConfig.builder().threadId(taskId.toString()).build();
        return graph.invoke(Map.of(ChainState.PROMPT, prompt), config).orElseThrow();
    }
}
