package io.adaptiveagents.app.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.app.SingletonPostgres;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.routing.RoutingService;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JaegerOtlpIntegrationTest.Config.class, properties = {
        "spring.main.web-application-type=none",
        "spring.ai.model.chat=none",
        "spring.application.name=adaptive-agent-runtime",
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.schedule-delay=100ms"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class JaegerOtlpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JaegerOtlpIntegrationTest.class);

    @Container
    static GenericContainer<?> jaeger = new GenericContainer<>(DockerImageName.parse("jaegertracing/all-in-one:1.62.0"))
            .withExposedPorts(4318, 16686)
            .withEnv("COLLECTOR_OTLP_ENABLED", "true")
            .waitingFor(Wait.forHttp("/").forPort(16686));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SingletonPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", SingletonPostgres::getUsername);
        registry.add("spring.datasource.password", SingletonPostgres::getPassword);

        registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
                () -> "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(4318) + "/v1/traces");
    }

    @SpringBootApplication(scanBasePackages = "io.adaptiveagents")
    static class Config {
        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure().dataSource(dataSource).load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        ChatModel chatModel() {
            return new QueueChatModel();
        }

        @Bean
        @Primary
        SearchTool searchTool() {
            return query -> List.of(new SearchResult(
                    "Adaptive Agents", "https://example.com/adaptive", "Adaptive agents runtime snippet"));
        }
    }

    static final class QueueChatModel implements ChatModel {
        private final List<Object> queue = new ArrayList<>();
        private int cursor = 0;

        synchronized void enqueue(ChatResponse... responses) {
            queue.addAll(List.of(responses));
        }

        synchronized void reset() {
            queue.clear();
            cursor = 0;
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            if (queue.isEmpty()) {
                return textResponse("adaptive agents summary text");
            }
            Object entry = queue.get(Math.min(cursor, queue.size() - 1));
            cursor++;
            return (ChatResponse) entry;
        }

        static ChatResponse textResponse(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    ExecutionRunner runner;

    @Autowired
    RoutingService routingService;

    @Autowired
    TaskStore taskStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    QueueChatModel chatModel;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
        chatModel.reset();
    }

    @Test
    @Timeout(60)
    void happyPathExportsTraceToJaegerAndQueriesRestApi() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskEnvelope envelope = new TaskEnvelope(
                taskId, "RESEARCH", "research adaptive agents and runtime", 1, null, null, Topology.CHAIN);
        taskStore.submitOrGet(envelope);
        routingService.route(envelope, System.currentTimeMillis());

        chatModel.enqueue(
                QueueChatModel.textResponse("search adaptive agents\nsearch runtime"),
                QueueChatModel.textResponse("Adaptive agents adjust behaviour at runtime."));

        CompletableFuture<ExecutionRunner.Outcome> future = runner.executeAsync(taskId);
        ExecutionRunner.Outcome outcome = future.get(30, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);

        // Poll Jaeger query REST API until trace with task_id appears
        String queryUrl = "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(16686)
                + "/api/traces?service=adaptive-agent-runtime";

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode matchingTrace = null;

        while (Instant.now().isBefore(deadline)) {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(queryUrl)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    for (JsonNode trace : data) {
                        if (traceHasTaskId(trace, taskId.toString())
                                && traceHasOperation(trace, "task-execution")
                                && traceHasOperation(trace, "cite")) {
                            matchingTrace = trace;
                            break;
                        }
                    }
                }
            }
            if (matchingTrace != null) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(matchingTrace)
                .as("Jaeger should return a trace for task %s within 30s", taskId)
                .isNotNull();

        String foundTraceId = matchingTrace.path("traceID").asText();
        JsonNode spans = matchingTrace.path("spans");
        assertThat(spans.isArray()).isTrue();
        assertThat(spans.size()).isGreaterThanOrEqualTo(5);

        log.info("Found Jaeger trace: traceId={}, totalSpans={}", foundTraceId, spans.size());

        // Verify node spans and tool spans
        List<String> operationNames = new ArrayList<>();
        int nodeSpanCount = 0;
        int toolSpanCount = 0;

        for (JsonNode span : spans) {
            String op = span.path("operationName").asText();
            operationNames.add(op);
            if (List.of("plan", "search", "summarize", "cite").contains(op)) {
                nodeSpanCount++;
                assertThat(spanHasTag(span, "task_id", taskId.toString())).isTrue();
            }
            if (List.of("web_search", "cite_sources").contains(op)) {
                toolSpanCount++;
                assertThat(spanHasTag(span, "task_id", taskId.toString())).isTrue();
            }
        }

        assertThat(nodeSpanCount).as("Must contain >= 1 node span").isGreaterThanOrEqualTo(1);
        assertThat(toolSpanCount).as("Must contain >= 1 tool span").isGreaterThanOrEqualTo(1);
        assertThat(operationNames).contains("task-execution", "plan", "search", "summarize", "cite", "web_search", "cite_sources");
    }

    private boolean traceHasTaskId(JsonNode trace, String taskId) {
        JsonNode spans = trace.path("spans");
        if (!spans.isArray()) {
            return false;
        }
        for (JsonNode span : spans) {
            if (spanHasTag(span, "task_id", taskId)) {
                return true;
            }
        }
        return false;
    }

    private boolean traceHasOperation(JsonNode trace, String operationName) {
        JsonNode spans = trace.path("spans");
        if (!spans.isArray()) {
            return false;
        }
        for (JsonNode span : spans) {
            if (operationName.equals(span.path("operationName").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean spanHasTag(JsonNode span, String key, String value) {
        JsonNode tags = span.path("tags");
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                if (key.equals(tag.path("key").asText()) && value.equals(tag.path("value").asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}
