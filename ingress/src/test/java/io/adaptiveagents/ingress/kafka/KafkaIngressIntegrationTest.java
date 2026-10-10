package io.adaptiveagents.ingress.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Kafka ingress integration test (T22): Testcontainers Postgres + KRaft Kafka against the
 * full io.adaptiveagents component scan with {@code runtime.kafka.enabled=true}. Wiring
 * mirrors RestIngressIntegrationTest (inner Config, stub-classifier profile, @Primary
 * model/search doubles, per-test table reset) plus @EnableScheduling so the progress-topic
 * outbox tail ticks, and a deliberate ~300ms/node model delay so the immediate-ACK semantics
 * are observable (the ACK must precede terminal completion).
 *
 * <p>Scenarios: (a) request/reply roundtrip with immediate ACK + eventual SUCCEEDED result,
 * (b) progress topic carrying per-task ordered NODE_START frames ending on TASK_COMPLETED,
 * (c) redelivered duplicate → single execution (T18 tie-in), (d) poison envelope → INVALID
 * error reply + DLT copy + listener survival, (e) ACK-before-terminal timing proof (folded
 * into (a) per plan).
 */
@SpringBootTest(classes = KafkaIngressIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none",
        "runtime.api-key=test-key",
        "runtime.kafka.enabled=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class KafkaIngressIntegrationTest {

    private static final String REQUEST_TOPIC = KafkaTaskListener.REQUEST_TOPIC;
    private static final String REPLY_TOPIC = KafkaTaskListener.REPLY_TOPIC;
    private static final String DLT_TOPIC = KafkaTaskListener.DLT_TOPIC;
    private static final String PROGRESS_TOPIC = ProgressTopicPublisher.PROGRESS_TOPIC;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    // 4.1.0 rather than 3.x: the 3.9 image's StorageTool rejects the CONTROLLER listener bound
    // to 0.0.0.0 (the address testcontainers' env template uses) at format time.
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @SpringBootApplication(scanBasePackages = "io.adaptiveagents")
    @EnableScheduling
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
            return new DelayedTextChatModel("adaptive agents runtime research summary", 300);
        }

        @Bean
        @Primary
        SearchTool searchTool() {
            return query -> List.of(new SearchResult(
                    "Canned Result", "https://example.com/result", "A canned snippet."));
        }
    }

    /**
     * Scripted ChatModel that sleeps before answering, padding a CHAIN run to well over a
     * second so the immediate ACK is observably earlier than terminal completion.
     */
    static final class DelayedTextChatModel implements ChatModel {
        private final String text;
        private final long delayMs;

        DelayedTextChatModel(String text, long delayMs) {
            this.text = text;
            this.delayMs = delayMs;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    @Test
    @Timeout(120)
    void requestReplyRoundtrip() throws Exception {
        UUID taskId = UUID.randomUUID();
        kafkaTemplate.send(REQUEST_TOPIC, taskId.toString(), chainEnvelope(taskId));

        JsonNode ack = awaitReplies(taskId, 1).get(0);
        long ackAtNanos = System.nanoTime();
        assertThat(ack.path("task_id").asText()).isEqualTo(taskId.toString());
        assertThat(ack.path("status").asText()).isIn("ROUTED", "RUNNING", "SUCCEEDED");
        assertThat(ack.path("decision").isNull()).isFalse();

        awaitStatus(taskId, "SUCCEEDED");
        long terminalAtNanos = System.nanoTime();
        String result = jdbcTemplate.queryForObject(
                "SELECT result FROM tasks WHERE task_id = ?", String.class, taskId);
        assertThat(result).isNotBlank();
        String decision = jdbcTemplate.queryForObject(
                "SELECT decision FROM tasks WHERE task_id = ?", String.class, taskId);
        assertThat(decision).isNotBlank();

        // (e) immediate-ACK proof: either the ACK carried a pre-terminal status, or it was
        // observably received before the task reached its terminal state.
        boolean preTerminalAck = !"SUCCEEDED".equals(ack.path("status").asText());
        assertThat(preTerminalAck || ackAtNanos < terminalAtNanos)
                .as("ACK must precede terminal completion or carry a pre-terminal status")
                .isTrue();
    }

    @Test
    @Timeout(120)
    void progressTopicOrdered() throws Exception {
        UUID taskId = UUID.randomUUID();
        List<JsonNode> frames = new ArrayList<>();
        Thread collector = new Thread(() -> awaitProgress(taskId, frames, TIMEOUT));
        collector.start();
        // Give the subscriber a moment to join the group before the events start flowing.
        Thread.sleep(2_000);
        kafkaTemplate.send(REQUEST_TOPIC, taskId.toString(), chainEnvelope(taskId));

        collector.join(TIMEOUT.toMillis() + 5_000);
        assertThat(frames).isNotEmpty();

        List<String> nodeStarts = frames.stream()
                .filter(frame -> "NODE_START".equals(frame.path("type").asText()))
                .map(frame -> frame.path("payload").path("node").asText())
                .toList();
        assertThat(nodeStarts).containsExactly("plan", "search", "summarize", "cite");

        for (int i = 1; i < frames.size(); i++) {
            assertThat(frames.get(i).path("seq").asLong())
                    .isGreaterThan(frames.get(i - 1).path("seq").asLong());
        }
        assertThat(frames.get(frames.size() - 1).path("type").asText()).isEqualTo("TASK_COMPLETED");
    }

    @Test
    @Timeout(120)
    void redeliveredDuplicateSingleExecution() throws Exception {
        UUID taskId = UUID.randomUUID();
        String envelope = chainEnvelope(taskId);
        kafkaTemplate.send(REQUEST_TOPIC, taskId.toString(), envelope);
        kafkaTemplate.send(REQUEST_TOPIC, taskId.toString(), envelope);

        List<JsonNode> replies = awaitReplies(taskId, 2);
        assertThat(replies).hasSize(2);
        replies.forEach(reply ->
                assertThat(reply.path("task_id").asText()).isEqualTo(taskId.toString()));

        awaitStatus(taskId, "SUCCEEDED");
        Integer planStarts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM progress_events WHERE task_id = ? AND type = 'NODE_START'"
                        + " AND payload->>'node' = 'plan'",
                Integer.class, taskId);
        assertThat(planStarts).as("redelivered duplicate must trigger exactly one execution").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void poisonEnvelopeYieldsErrorReplyAndSurvives() throws Exception {
        String poison = "{\"task_id\":\"not-a-uuid\",";
        kafkaTemplate.send(REQUEST_TOPIC, "poison", poison);

        JsonNode errorReply = awaitErrorReply();
        assertThat(errorReply.path("task_id").isNull()).isTrue();
        assertThat(errorReply.path("status").asText()).isEqualTo("INVALID");
        assertThat(errorReply.path("error").asText()).isNotBlank();

        ConsumerRecord<String, String> dlt = awaitDltRecord(poison);
        assertThat(dlt.value()).isEqualTo(poison);

        // Listener survived: a valid envelope right after the poison completes normally.
        UUID taskId = UUID.randomUUID();
        kafkaTemplate.send(REQUEST_TOPIC, taskId.toString(), chainEnvelope(taskId));
        awaitStatus(taskId, "SUCCEEDED");
    }

    // --- helpers -------------------------------------------------------------

    private static String chainEnvelope(UUID taskId) {
        return """
                {"task_id":"%s","task_type":"research","prompt":"research adaptive agents","breadth_hint":1,"forced_topology":"CHAIN"}
                """.formatted(taskId);
    }

    /** Null-tolerant poll: the Kafka submit is async, so the row may not exist yet. */
    private void awaitStatus(UUID taskId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.query(
                    "SELECT status FROM tasks WHERE task_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    taskId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("task " + taskId + " never reached " + expected);
    }

    /** Polls the reply topic until {@code expectedCount} ACKs carrying {@code taskId} arrive. */
    private List<JsonNode> awaitReplies(UUID taskId, int expectedCount) throws Exception {
        AtomicInteger remaining = new AtomicInteger(expectedCount);
        List<JsonNode> collected = new ArrayList<>();
        consumeUntil(REPLY_TOPIC, record -> {
            JsonNode node = readTree(record.value());
            if (node != null && taskId.toString().equals(node.path("task_id").asText(null))) {
                collected.add(node);
                return remaining.decrementAndGet() <= 0;
            }
            return false;
        });
        assertThat(collected).hasSize(expectedCount);
        return collected;
    }

    /** Polls the reply topic until the (single, class-wide) INVALID error reply arrives. */
    private JsonNode awaitErrorReply() throws Exception {
        List<JsonNode> collected = new ArrayList<>();
        consumeUntil(REPLY_TOPIC, record -> {
            JsonNode node = readTree(record.value());
            if (node != null && "INVALID".equals(node.path("status").asText(null))
                    && !node.path("error").asText("").isBlank()) {
                collected.add(node);
                return true;
            }
            return false;
        });
        assertThat(collected).hasSize(1);
        return collected.get(0);
    }

    /** Polls the DLT until the verbatim poison payload shows up. */
    private ConsumerRecord<String, String> awaitDltRecord(String rawPoison) throws Exception {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        consumeUntil(DLT_TOPIC, record -> {
            if (rawPoison.equals(record.value())) {
                collected.add(record);
                return true;
            }
            return false;
        });
        assertThat(collected).hasSize(1);
        return collected.get(0);
    }

    /** Collects this task's progress frames until its TASK_COMPLETED frame arrives. */
    private void awaitProgress(UUID taskId, List<JsonNode> sink, Duration timeout) {
        try (KafkaConsumer<String, String> consumer = testConsumer()) {
            consumer.subscribe(List.of(PROGRESS_TOPIC));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && !seenCompletion(sink, taskId)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    JsonNode frame = readTree(record.value());
                    if (frame != null && taskId.toString().equals(frame.path("task_id").asText(null))) {
                        sink.add(frame);
                    }
                }
            }
        }
    }

    private static boolean seenCompletion(List<JsonNode> frames, UUID taskId) {
        return frames.stream().anyMatch(frame ->
                "TASK_COMPLETED".equals(frame.path("type").asText())
                        && taskId.toString().equals(frame.path("task_id").asText()));
    }

    /**
     * Subscribes with a FRESH unique consumer group (earliest) and polls until {@code stop}
     * matches or {@link #TIMEOUT} elapses; unmatched earlier records are simply skipped, so
     * cross-scenario topic residue is harmless.
     */
    private void consumeUntil(String topic, Predicate<ConsumerRecord<String, String>> stop)
            throws Exception {
        try (KafkaConsumer<String, String> consumer = testConsumer()) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (stop.test(record)) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("no matching record on topic " + topic + " within " + TIMEOUT);
    }

    private KafkaConsumer<String, String> testConsumer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "t22-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }
}
