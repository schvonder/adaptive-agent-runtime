package io.adaptiveagents.app.tracing;

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

/**
 * QA failure scenario test (T28): verifies that when the OTLP exporter endpoint is completely
 * down/unreachable (connection refused), tracing failure is strictly non-fatal and task execution
 * completes successfully with status SUCCEEDED.
 */
@SpringBootTest(classes = ExporterDownNonFatalTest.Config.class, properties = {
        "spring.main.web-application-type=none",
        "spring.ai.model.chat=none",
        "spring.application.name=adaptive-agent-runtime",
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:59999/v1/traces",
        "management.opentelemetry.tracing.export.otlp.connect-timeout=500ms",
        "management.opentelemetry.tracing.export.otlp.timeout=500ms",
        "management.opentelemetry.tracing.export.schedule-delay=100ms"
})
@ActiveProfiles("stub-classifier")
class ExporterDownNonFatalTest {

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

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
        chatModel.reset();
    }

    @Test
    @Timeout(30)
    void exporterDownEndpointIsNonFatalAndTaskExecutionSucceeds() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskEnvelope envelope = new TaskEnvelope(
                taskId, "RESEARCH", "research with exporter down", 1, null, null, Topology.CHAIN);
        taskStore.submitOrGet(envelope);
        routingService.route(envelope, System.currentTimeMillis());

        chatModel.enqueue(
                QueueChatModel.textResponse("search query\nsearch runtime"),
                QueueChatModel.textResponse("Completed summary despite exporter down."));

        CompletableFuture<ExecutionRunner.Outcome> future = runner.executeAsync(taskId);
        ExecutionRunner.Outcome outcome = future.get(20, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(outcome.detail()).contains("Completed summary despite exporter down.");

        // Database row also reflects SUCCEEDED
        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tasks WHERE task_id = ?", String.class, taskId);
        assertThat(dbStatus).isEqualTo("SUCCEEDED");
    }
}
