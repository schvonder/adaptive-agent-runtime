package io.adaptiveagents.app.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.app.SingletonPostgres;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.progress.ProgressEvent;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.routing.DecisionSource;
import io.adaptiveagents.topologies.routing.RoutingDecision;
import io.adaptiveagents.topologies.routing.RoutingService;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CapturingSpanExporterTest.Config.class, properties = {
        "spring.main.web-application-type=none",
        "spring.ai.model.chat=none",
        "management.tracing.sampling.probability=1.0"
})
@ActiveProfiles("stub-classifier")
class CapturingSpanExporterTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SingletonPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", SingletonPostgres::getUsername);
        registry.add("spring.datasource.password", SingletonPostgres::getPassword);
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
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SpanProcessor simpleSpanProcessor(InMemorySpanExporter inMemorySpanExporter) {
            return SimpleSpanProcessor.create(inMemorySpanExporter);
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
        private java.util.function.Consumer<Prompt> onCall;

        void setOnCall(java.util.function.Consumer<Prompt> onCall) {
            this.onCall = onCall;
        }

        synchronized void enqueue(ChatResponse... responses) {
            queue.addAll(List.of(responses));
        }

        synchronized void reset() {
            queue.clear();
            cursor = 0;
            onCall = null;
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            if (onCall != null) {
                onCall.accept(prompt);
            }
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
    ProgressService progressService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    InMemorySpanExporter inMemorySpanExporter;

    @Autowired
    QueueChatModel chatModel;

    @Autowired
    io.adaptiveagents.workload.tools.ResearchTools researchTools;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
        chatModel.reset();
        inMemorySpanExporter.reset();
    }

    @Test
    @Timeout(30)
    void chainExecutionRecordsTaskNodeAndToolSpans() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskEnvelope envelope = new TaskEnvelope(
                taskId, "RESEARCH", "research adaptive agents", 1, null, null, Topology.CHAIN);
        taskStore.submitOrGet(envelope);
        routingService.route(envelope, System.currentTimeMillis());

        chatModel.enqueue(
                QueueChatModel.textResponse("search adaptive agents\nsearch runtime"),
                QueueChatModel.textResponse("Adaptive agents adjust behaviour at runtime."));

        CompletableFuture<ExecutionRunner.Outcome> future = runner.executeAsync(taskId);
        ExecutionRunner.Outcome outcome = future.get(20, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);

        List<SpanData> finishedSpans = inMemorySpanExporter.getFinishedSpanItems();
        assertThat(finishedSpans).isNotEmpty();

        // 1. Root task span
        SpanData taskSpan = finishedSpans.stream()
                .filter(s -> "task-execution".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing task-execution span. Got: " + spanNames(finishedSpans)));
        assertThat(taskSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());

        String traceId = taskSpan.getTraceId();
        assertThat(traceId).isNotBlank();

        // 2. Node spans for CHAIN
        for (String nodeName : List.of("plan", "search", "summarize", "cite")) {
            SpanData nodeSpan = finishedSpans.stream()
                    .filter(s -> nodeName.equals(s.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing node span for " + nodeName));
            assertThat(nodeSpan.getTraceId()).isEqualTo(traceId);
            assertThat(nodeSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());
            assertThat(nodeSpan.getAttributes().get(AttributeKey.stringKey("node"))).isEqualTo(nodeName);
            assertThat(nodeSpan.getAttributes().get(AttributeKey.stringKey("topology"))).isEqualTo("CHAIN");
        }

        // 3. Tool spans
        SpanData searchToolSpan = finishedSpans.stream()
                .filter(s -> "web_search".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing web_search tool span. Got: " + spanNames(finishedSpans)));
        assertThat(searchToolSpan.getTraceId()).isEqualTo(traceId);
        assertThat(searchToolSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());

        SpanData citeToolSpan = finishedSpans.stream()
                .filter(s -> "cite_sources".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing cite_sources tool span. Got: " + spanNames(finishedSpans)));
        assertThat(citeToolSpan.getTraceId()).isEqualTo(traceId);
        assertThat(citeToolSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());

        // 4. Progress events traceId check
        List<ProgressEvent> events = progressService.sinceSeq(taskId, 0);
        assertThat(events).isNotEmpty();
        for (ProgressEvent event : events) {
            assertThat(event.payload()).isNotNull();
            JsonNode payloadJson = mapper.readTree(event.payload());
            assertThat(payloadJson.has("traceId"))
                    .as("Event %s payload should have traceId", event.type())
                    .isTrue();
            assertThat(payloadJson.get("traceId").asText()).isEqualTo(traceId);
        }
    }

    @Test
    @Timeout(30)
    void soloExecutionRecordsTaskNodeAndToolSpans() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskEnvelope envelope = new TaskEnvelope(
                taskId, "RESEARCH", "research adaptive agents", 1, null, null, Topology.SOLO);
        taskStore.submitOrGet(envelope);
        routingService.route(envelope, System.currentTimeMillis());

        chatModel.setOnCall(prompt -> researchTools.web_search("adaptive agents"));
        chatModel.enqueue(QueueChatModel.textResponse("Adaptive agents are autonomous systems."));

        CompletableFuture<ExecutionRunner.Outcome> future = runner.executeAsync(taskId);
        ExecutionRunner.Outcome outcome = future.get(20, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);

        List<SpanData> finishedSpans = inMemorySpanExporter.getFinishedSpanItems();
        assertThat(finishedSpans).isNotEmpty();

        // 1. Root task span
        SpanData taskSpan = finishedSpans.stream()
                .filter(s -> "task-execution".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing task-execution span. Got: " + spanNames(finishedSpans)));
        assertThat(taskSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());
        String traceId = taskSpan.getTraceId();

        // 2. Node span for SOLO
        SpanData nodeSpan = finishedSpans.stream()
                .filter(s -> "solo-agent".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing solo-agent node span"));
        assertThat(nodeSpan.getTraceId()).isEqualTo(traceId);
        assertThat(nodeSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());
        assertThat(nodeSpan.getAttributes().get(AttributeKey.stringKey("node"))).isEqualTo("solo-agent");
        assertThat(nodeSpan.getAttributes().get(AttributeKey.stringKey("topology"))).isEqualTo("SOLO");

        // 3. Tool span
        SpanData toolSpan = finishedSpans.stream()
                .filter(s -> "web_search".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing web_search tool span"));
        assertThat(toolSpan.getTraceId()).isEqualTo(traceId);
        assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey("task_id"))).isEqualTo(taskId.toString());

        // 4. Progress events traceId check
        List<ProgressEvent> events = progressService.sinceSeq(taskId, 0);
        assertThat(events).isNotEmpty();
        for (ProgressEvent event : events) {
            JsonNode payloadJson = mapper.readTree(event.payload());
            assertThat(payloadJson.has("traceId")).isTrue();
            assertThat(payloadJson.get("traceId").asText()).isEqualTo(traceId);
        }
    }

    private static List<String> spanNames(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }
}
