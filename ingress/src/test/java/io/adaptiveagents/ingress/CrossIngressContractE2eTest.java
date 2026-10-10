package io.adaptiveagents.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
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
 * Cross-protocol contract parity e2e test (T24): validates that identical task envelopes
 * submitted across all three transports (REST POST /tasks, Kafka request/reply topic, MCP tool)
 * produce identical topology decisions, identical decision JSON payloads (modulo source rules),
 * and hash-identical terminal task results under a deterministic fixture model.
 */
@SpringBootTest(classes = CrossIngressContractE2eTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.chat=none",
                "runtime.api-key=test-key",
                "runtime.kafka.enabled=true",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "spring.ai.mcp.client.toolcallback.enabled=false",
                "spring.ai.mcp.client.streamable-http.connections.server1.url=http://localhost:${local.server.port}"
        })
@ActiveProfiles("stub-classifier")
@Testcontainers
class CrossIngressContractE2eTest {

    private static final String REQUEST_TOPIC = "adaptive-tasks-request";
    private static final String REPLY_TOPIC = "adaptive-tasks-reply";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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
        static BeanFactoryPostProcessor lazyMcpClients() {
            return beanFactory -> {
                for (String name : beanFactory.getBeanDefinitionNames()) {
                    BeanDefinition bd = beanFactory.getBeanDefinition(name);
                    String beanClassName = bd.getBeanClassName();
                    if (name.contains("mcpSyncClients") || name.contains("makeSyncClientsClosable")
                            || name.contains("streamableHttpHttpClientTransports")
                            || name.contains("mcpAsyncClients") || name.contains("makeAsyncClientsClosable")
                            || name.contains("McpStreamableHttpClientProperties")
                            || (beanClassName != null && beanClassName.contains(".client."))) {
                        bd.setLazyInit(true);
                    }
                }
            };
        }

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
            return new FixedTextChatModel("adaptive agents runtime research summary");
        }

        @Bean
        @Primary
        SearchTool searchTool() {
            return query -> List.of(new SearchResult(
                    "Canned Result", "https://example.com/result", "A canned snippet."));
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
    @Lazy
    List<McpSyncClient> mcpSyncClients;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @LocalServerPort
    int serverPort;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    private McpSyncClient mcpClient() {
        assertThat(mcpSyncClients).isNotEmpty();
        return mcpSyncClients.getFirst();
    }

    @Test
    @Timeout(60)
    void crossProtocolContractParity() throws Exception {
        String prompt = "research cross-protocol contract parity";
        int breadthHint = 1;
        Topology forcedTopology = Topology.CHAIN;

        UUID restTaskId = UUID.randomUUID();
        UUID kafkaTaskId = UUID.randomUUID();
        UUID mcpTaskId = UUID.randomUUID();

        HttpClient httpClient = HttpClient.newHttpClient();
        String restBody = objectMapper.writeValueAsString(new TaskEnvelope(
                restTaskId, "research", prompt, breadthHint, null, null, forcedTopology));
        HttpRequest restRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/tasks"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "test-key")
                .POST(HttpRequest.BodyPublishers.ofString(restBody))
                .build();
        HttpResponse<String> restResponse = httpClient.send(restRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(restResponse.statusCode()).isEqualTo(202);
        awaitStatus(restTaskId, "SUCCEEDED");

        String kafkaPayload = objectMapper.writeValueAsString(new TaskEnvelope(
                kafkaTaskId, "research", prompt, breadthHint, null, null, forcedTopology));
        kafkaTemplate.send(REQUEST_TOPIC, kafkaTaskId.toString(), kafkaPayload);
        JsonNode kafkaAck = awaitReplies(kafkaTaskId, 1).getFirst();
        assertThat(kafkaAck.path("task_id").asText()).isEqualTo(kafkaTaskId.toString());
        awaitStatus(kafkaTaskId, "SUCCEEDED");

        McpSchema.CallToolResult mcpResult = mcpClient().callTool(new McpSchema.CallToolRequest("submit_task", Map.of(
                "task_id", mcpTaskId.toString(),
                "prompt", prompt,
                "breadth_hint", breadthHint,
                "forced_topology", forcedTopology.name()
        )));
        assertThat(mcpResult.isError()).isNotEqualTo(Boolean.TRUE);
        awaitStatus(mcpTaskId, "SUCCEEDED");

        TaskDbState restDb = loadTaskDbState(restTaskId);
        TaskDbState kafkaDb = loadTaskDbState(kafkaTaskId);
        TaskDbState mcpDb = loadTaskDbState(mcpTaskId);

        assertThat(restDb.topology).isEqualTo("CHAIN");
        assertThat(kafkaDb.topology).isEqualTo("CHAIN");
        assertThat(mcpDb.topology).isEqualTo("CHAIN");

        assertThat(restDb.decisionJson).isEqualTo(kafkaDb.decisionJson).isEqualTo(mcpDb.decisionJson);

        String normRest = normalizeResult(restDb.resultJson);
        String normKafka = normalizeResult(kafkaDb.resultJson);
        String normMcp = normalizeResult(mcpDb.resultJson);

        assertThat(normRest).isEqualTo(normKafka).isEqualTo(normMcp);

        String hashRest = sha256Hex(normRest);
        String hashKafka = sha256Hex(normKafka);
        String hashMcp = sha256Hex(normMcp);

        assertThat(hashRest).isEqualTo(hashKafka).isEqualTo(hashMcp);
    }

    @Test
    @Timeout(60)
    void forcedTopologyOverrideParity() throws Exception {
        String prompt = "test forced topology parity";
        Topology forcedTopology = Topology.FAN_OUT;

        UUID restTaskId = UUID.randomUUID();
        UUID kafkaTaskId = UUID.randomUUID();
        UUID mcpTaskId = UUID.randomUUID();

        HttpClient httpClient = HttpClient.newHttpClient();
        String restBody = objectMapper.writeValueAsString(new TaskEnvelope(
                restTaskId, "research", prompt, 1, null, null, forcedTopology));
        HttpRequest restRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/tasks"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "test-key")
                .POST(HttpRequest.BodyPublishers.ofString(restBody))
                .build();
        HttpResponse<String> restResponse = httpClient.send(restRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(restResponse.statusCode()).isEqualTo(202);
        awaitStatus(restTaskId, "SUCCEEDED");

        String kafkaPayload = objectMapper.writeValueAsString(new TaskEnvelope(
                kafkaTaskId, "research", prompt, 1, null, null, forcedTopology));
        kafkaTemplate.send(REQUEST_TOPIC, kafkaTaskId.toString(), kafkaPayload);
        JsonNode kafkaAck = awaitReplies(kafkaTaskId, 1).getFirst();
        assertThat(kafkaAck.path("task_id").asText()).isEqualTo(kafkaTaskId.toString());
        awaitStatus(kafkaTaskId, "SUCCEEDED");

        McpSchema.CallToolResult mcpResult = mcpClient().callTool(new McpSchema.CallToolRequest("submit_task", Map.of(
                "task_id", mcpTaskId.toString(),
                "prompt", prompt,
                "forced_topology", forcedTopology.name()
        )));
        assertThat(mcpResult.isError()).isNotEqualTo(Boolean.TRUE);
        awaitStatus(mcpTaskId, "SUCCEEDED");

        TaskDbState restDb = loadTaskDbState(restTaskId);
        TaskDbState kafkaDb = loadTaskDbState(kafkaTaskId);
        TaskDbState mcpDb = loadTaskDbState(mcpTaskId);

        assertThat(restDb.topology).isEqualTo("FAN_OUT");
        assertThat(kafkaDb.topology).isEqualTo("FAN_OUT");
        assertThat(mcpDb.topology).isEqualTo("FAN_OUT");

        JsonNode restDecision = objectMapper.readTree(restDb.decisionJson);
        JsonNode kafkaDecision = objectMapper.readTree(kafkaDb.decisionJson);
        JsonNode mcpDecision = objectMapper.readTree(mcpDb.decisionJson);

        assertThat(restDecision.path("source").asText()).isEqualTo("OVERRIDE");
        assertThat(kafkaDecision.path("source").asText()).isEqualTo("OVERRIDE");
        assertThat(mcpDecision.path("source").asText()).isEqualTo("OVERRIDE");

        assertThat(restDb.decisionJson).isEqualTo(kafkaDb.decisionJson).isEqualTo(mcpDb.decisionJson);
    }

    @Test
    @Timeout(60)
    void authMatrixParity() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();

        HttpRequest restWithoutKey = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> rest401 = httpClient.send(restWithoutKey, HttpResponse.BodyHandlers.ofString());
        assertThat(rest401.statusCode()).isEqualTo(401);

        UUID restId = UUID.randomUUID();
        String validRest = objectMapper.writeValueAsString(new TaskEnvelope(
                restId, "research", "auth matrix prompt", 1, null, null, Topology.CHAIN));
        HttpRequest restWithKey = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/tasks"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "test-key")
                .POST(HttpRequest.BodyPublishers.ofString(validRest))
                .build();
        HttpResponse<String> rest202 = httpClient.send(restWithKey, HttpResponse.BodyHandlers.ofString());
        assertThat(rest202.statusCode()).isEqualTo(202);
        awaitStatus(restId, "SUCCEEDED");

        HttpRequest mcpGet = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/mcp"))
                .GET()
                .build();
        HttpResponse<String> mcpGetResp = httpClient.send(mcpGet, HttpResponse.BodyHandlers.ofString());
        assertThat(mcpGetResp.statusCode()).isNotEqualTo(401);

        UUID mcpId = UUID.randomUUID();
        McpSchema.CallToolResult mcpRes = mcpClient().callTool(new McpSchema.CallToolRequest("submit_task", Map.of(
                "task_id", mcpId.toString(),
                "prompt", "mcp auth check",
                "forced_topology", "CHAIN"
        )));
        assertThat(mcpRes.isError()).isNotEqualTo(Boolean.TRUE);
        awaitStatus(mcpId, "SUCCEEDED");

        UUID kafkaId = UUID.randomUUID();
        String kafkaPayload = objectMapper.writeValueAsString(new TaskEnvelope(
                kafkaId, "research", "kafka auth check", 1, null, null, Topology.CHAIN));
        kafkaTemplate.send(REQUEST_TOPIC, kafkaId.toString(), kafkaPayload);
        JsonNode kafkaAck = awaitReplies(kafkaId, 1).getFirst();
        assertThat(kafkaAck.path("task_id").asText()).isEqualTo(kafkaId.toString());
        awaitStatus(kafkaId, "SUCCEEDED");
    }

    private record TaskDbState(String topology, String decisionJson, String resultJson) {}

    private TaskDbState loadTaskDbState(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT topology, decision, result::text FROM tasks WHERE task_id = ?",
                (rs, rowNum) -> new TaskDbState(
                        rs.getString("topology"),
                        rs.getString("decision"),
                        rs.getString(3)),
                taskId);
    }

    private String normalizeResult(String rawResult) throws Exception {
        JsonNode node = objectMapper.readTree(rawResult);
        if (node instanceof ObjectNode objectNode) {
            objectNode.remove("task_id");
            objectNode.remove("timestamp");
            objectNode.remove("created_at");
            objectNode.remove("updated_at");
        }
        return objectMapper.writeValueAsString(node);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

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
        props.put("group.id", "contract-" + UUID.randomUUID());
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
