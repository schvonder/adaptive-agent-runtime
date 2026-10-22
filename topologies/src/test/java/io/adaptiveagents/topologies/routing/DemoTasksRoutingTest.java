package io.adaptiveagents.topologies.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.classify.Band;
import io.adaptiveagents.core.classify.ComplexityClassifier;
import io.adaptiveagents.core.classify.ComplexityVerdict;
import io.adaptiveagents.core.classify.LlmClassifier;
import io.adaptiveagents.core.classify.RuleScorer;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.workload.tools.Citation;
import io.adaptiveagents.workload.tools.CiteTool;
import io.adaptiveagents.workload.tools.FetchTool;
import io.adaptiveagents.workload.tools.FixtureFetchAdapter;
import io.adaptiveagents.workload.tools.FixtureSearchAdapter;
import io.adaptiveagents.workload.tools.ResearchTools;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = DemoTasksRoutingTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class DemoTasksRoutingTest {

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
            return new FixedTextChatModel("{\"band\":\"AMBIGUOUS\",\"topology\":\"CHAIN\",\"rationale\":\"Resolved ambiguous band to CHAIN via LLM leg\"}");
        }

        @Bean
        ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
            return ChatClient.builder(chatModel);
        }

        @Bean
        @Primary
        ComplexityClassifier realClassifierPipeline(RuleScorer ruleScorer, ChatClient.Builder chatClientBuilder) {
            return new LlmClassifier(chatClientBuilder, ruleScorer, "");
        }
    }

    static final class FixedTextChatModel implements ChatModel {
        private final String text;

        FixedTextChatModel(String text) {
            this.text = text;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    RoutingService routingService;

    @Autowired
    RuleScorer ruleScorer;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    private List<TaskEnvelope> loadDemoTasks() throws Exception {
        ClassPathResource resource = new ClassPathResource("tasks/demo-tasks.json");
        assertThat(resource.exists()).isTrue();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<TaskEnvelope>>() {});
        }
    }

    @Test
    @Timeout(30)
    void demoTasksDistributionAndRouting() throws Exception {
        List<TaskEnvelope> tasks = loadDemoTasks();
        assertThat(tasks).hasSize(12);

        Map<Topology, Integer> expectedCounts = Map.of(
                Topology.SOLO, 4,
                Topology.CHAIN, 4,
                Topology.FAN_OUT, 2,
                Topology.SUPERVISOR, 2
        );

        Map<Topology, Integer> actualCounts = new java.util.EnumMap<>(Topology.class);

        long nowMs = 1_000_000L;
        for (TaskEnvelope envelope : tasks) {
            ComplexityVerdict ruleVerdict = ruleScorer.score(envelope, nowMs);
            RoutingDecision decision = routingService.route(envelope, nowMs);

            Topology routedTopology = decision.topology();
            actualCounts.put(routedTopology, actualCounts.getOrDefault(routedTopology, 0) + 1);

            switch (routedTopology) {
                case SOLO -> {
                    assertThat(ruleVerdict.band()).isEqualTo(Band.CLEAR_SIMPLE);
                    assertThat(ruleVerdict.score()).isLessThanOrEqualTo(0);
                    assertThat(decision.source()).isEqualTo(DecisionSource.RULES);
                }
                case CHAIN -> {
                    assertThat(ruleVerdict.band()).isEqualTo(Band.AMBIGUOUS);
                    assertThat(ruleVerdict.score()).isBetween(1, 19);
                    assertThat(decision.source()).isEqualTo(DecisionSource.LLM);
                }
                case FAN_OUT -> {
                    assertThat(ruleVerdict.band()).isEqualTo(Band.CLEAR_COMPLEX);
                    assertThat(ruleVerdict.score()).isGreaterThanOrEqualTo(20);
                    assertThat(envelope.breadthHint()).isLessThan(4);
                    assertThat(decision.source()).isEqualTo(DecisionSource.RULES);
                }
                case SUPERVISOR -> {
                    assertThat(ruleVerdict.band()).isEqualTo(Band.CLEAR_COMPLEX);
                    assertThat(ruleVerdict.score()).isGreaterThanOrEqualTo(20);
                    assertThat(envelope.breadthHint()).isGreaterThanOrEqualTo(4);
                    assertThat(decision.source()).isEqualTo(DecisionSource.RULES);
                }
            }

            String persistedTopology = jdbcTemplate.queryForObject(
                    "SELECT topology FROM tasks WHERE task_id = ?", String.class, envelope.taskId());
            assertThat(persistedTopology).isEqualTo(routedTopology.name());
        }

        for (Map.Entry<Topology, Integer> entry : expectedCounts.entrySet()) {
            assertThat(actualCounts.get(entry.getKey()))
                    .as("Count for topology %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @Timeout(30)
    void contradictingHintsLandInAmbiguousBand() {
        UUID contradictingId = UUID.fromString("00000000-0000-4000-8000-000000000099");
        TaskEnvelope contradictingTask = new TaskEnvelope(
                contradictingId,
                "research",
                "Briefly define what an agent is.",
                4,
                null,
                null,
                null
        );

        long nowMs = 1_000_000L;
        ComplexityVerdict verdict = ruleScorer.score(contradictingTask, nowMs);

        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.score()).isEqualTo(10);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(verdict.rationale()).contains("Short prompt (<50 tokens) [-10]");
        assertThat(verdict.rationale()).contains("Breadth >= 4 [+20]");

        RoutingDecision decision = routingService.route(contradictingTask, nowMs);
        assertThat(decision.topology()).isEqualTo(Topology.CHAIN);
        assertThat(decision.source()).isEqualTo(DecisionSource.LLM);
    }

    @Test
    @Timeout(30)
    void fixturesLoadForEveryTask() throws Exception {
        List<TaskEnvelope> tasks = loadDemoTasks();
        assertThat(tasks).hasSize(12);

        Map<UUID, String> taskQueries = Map.ofEntries(
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000001"), "single agent architecture"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000002"), "agent tool calling protocols"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000003"), "memory persistence in agent systems"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000004"), "agent guardrails and safety boundaries"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000005"), "sequential verification pipeline"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000006"), "stepwise literature synthesis"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000007"), "iterative code generation and review"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000008"), "data pipeline diagnostics chain"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000009"), "distributed consensus protocols"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000010"), "multi perspective llm evaluation"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000011"), "autonomous systems governance"),
                Map.entry(UUID.fromString("00000000-0000-4000-8000-000000000012"), "enterprise multi agent orchestration")
        );

        for (TaskEnvelope task : tasks) {
            String query = taskQueries.get(task.taskId());
            assertThat(query).isNotNull();

            FixtureSearchAdapter taskSearchAdapter = new FixtureSearchAdapter("fixtures/tasks/" + task.taskId() + "/search");
            List<SearchResult> taskSearchResults = taskSearchAdapter.search(query);
            assertThat(taskSearchResults)
                    .as("Search results for task %s (query '%s')", task.taskId(), query)
                    .isNotEmpty();

            FixtureFetchAdapter taskFetchAdapter = new FixtureFetchAdapter("fixtures/tasks/" + task.taskId() + "/pages");
            for (SearchResult result : taskSearchResults) {
                String content = taskFetchAdapter.fetch(result.url());
                assertThat(content)
                        .as("Fetched page for url '%s' under task %s", result.url(), task.taskId())
                        .isNotBlank();
            }

            FixtureSearchAdapter defaultSearchAdapter = new FixtureSearchAdapter();
            List<SearchResult> defaultSearchResults = defaultSearchAdapter.search(query);
            assertThat(defaultSearchResults)
                    .as("Default search results for query '%s'", query)
                    .isNotEmpty();
        }

        UUID task13Id = UUID.fromString("00000000-0000-4000-8000-000000000099");
        FixtureSearchAdapter task13Search = new FixtureSearchAdapter("fixtures/tasks/" + task13Id + "/search");
        List<SearchResult> task13Results = task13Search.search("agent definition");
        assertThat(task13Results).isNotEmpty();

        FixtureFetchAdapter task13Fetch = new FixtureFetchAdapter("fixtures/tasks/" + task13Id + "/pages");
        String task13Content = task13Fetch.fetch(task13Results.get(0).url());
        assertThat(task13Content).isNotBlank();
    }
}
