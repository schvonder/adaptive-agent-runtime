package io.adaptiveagents.ingress.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MCP ingress integration test (T23): Testcontainers Postgres + random-port embedded web server
 * exposing the streamable-http MCP server on {@code /mcp}. The test drives the server via the
 * autoconfigured Spring AI test-scope {@link McpSyncClient}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Full roundtrip: submit_task with forced_topology=CHAIN -> poll get_task -> get_task_result</li>
 *   <li>Idempotent dedup: immediate duplicate submit_task returns same task_id without re-execution</li>
 *   <li>Coexistence / auth exemption: MCP requires no API key while /tasks requires X-API-Key</li>
 *   <li>Unknown task_id: returns CallToolResult with isError=true (not a protocol error)</li>
 *   <li>Invalid arguments: validation errors surface as isError=true mentioning the rule</li>
 * </ul>
 */
@SpringBootTest(classes = McpIngressIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.chat=none",
                "runtime.api-key=test-key",
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "spring.ai.mcp.client.toolcallback.enabled=false",
                "spring.ai.mcp.client.streamable-http.connections.server1.url=http://localhost:${local.server.port}"
        })
@ActiveProfiles("stub-classifier")
@Testcontainers
class McpIngressIntegrationTest {

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
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor lazyMcpClients() {
            return beanFactory -> {
                for (String name : beanFactory.getBeanDefinitionNames()) {
                    org.springframework.beans.factory.config.BeanDefinition bd = beanFactory.getBeanDefinition(name);
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
    @org.springframework.context.annotation.Lazy
    List<McpSyncClient> mcpSyncClients;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int serverPort;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    private McpSyncClient client() {
        assertThat(mcpSyncClients).isNotEmpty();
        return mcpSyncClients.getFirst();
    }

    private JsonNode parseToolResult(McpSchema.CallToolResult result) throws Exception {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).isNotEmpty();
        McpSchema.Content content = result.content().getFirst();
        assertThat(content).isInstanceOf(McpSchema.TextContent.class);
        String text = ((McpSchema.TextContent) content).text();
        return objectMapper.readTree(text);
    }

    private String extractErrorText(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isNotEmpty();
        McpSchema.Content content = result.content().getFirst();
        assertThat(content).isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) content).text();
    }

    @Test
    @Timeout(60)
    void fullRoundtripSubmitAndPoll() throws Exception {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> submitArgs = Map.of(
                "task_id", taskId.toString(),
                "prompt", "research autonomous agent patterns",
                "forced_topology", "CHAIN"
        );

        McpSchema.CallToolResult submitResult = client().callTool(
                new McpSchema.CallToolRequest("submit_task", submitArgs));
        JsonNode submitNode = parseToolResult(submitResult);
        assertThat(submitNode.get("task_id").asText()).isEqualTo(taskId.toString());
        assertThat(submitNode.get("status").asText()).isIn("SUBMITTED", "ROUTED", "RUNNING");

        String currentStatus = jdbcTemplate.query(
                "SELECT status FROM tasks WHERE task_id = ?",
                rs -> rs.next() ? rs.getString("status") : null, taskId);
        if (currentStatus != null && !"SUCCEEDED".equals(currentStatus)) {
            McpSchema.CallToolResult earlyResult = client().callTool(
                    new McpSchema.CallToolRequest("get_task_result", Map.of("task_id", taskId.toString())));
            assertThat(earlyResult.isError()).isTrue();
            String errText = extractErrorText(earlyResult);
            assertThat(errText).contains("task is not finished yet");
        }

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode taskStateNode = null;
        while (Instant.now().isBefore(deadline)) {
            McpSchema.CallToolResult getResult = client().callTool(
                    new McpSchema.CallToolRequest("get_task", Map.of("task_id", taskId.toString())));
            taskStateNode = parseToolResult(getResult);
            if ("SUCCEEDED".equals(taskStateNode.get("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(taskStateNode).isNotNull();
        assertThat(taskStateNode.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(taskStateNode.get("topology").asText()).isEqualTo("CHAIN");
        assertThat(taskStateNode.get("result").asText()).isNotEmpty();
        assertThat(taskStateNode.get("error").isNull() || "null".equals(taskStateNode.get("error").asText()))
                .isTrue();

        McpSchema.CallToolResult resultOutcome = client().callTool(
                new McpSchema.CallToolRequest("get_task_result", Map.of("task_id", taskId.toString())));
        JsonNode outcomeNode = parseToolResult(resultOutcome);
        assertThat(outcomeNode.get("task_id").asText()).isEqualTo(taskId.toString());
        assertThat(outcomeNode.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(outcomeNode.get("result").asText()).isEqualTo(taskStateNode.get("result").asText());
    }

    @Test
    @Timeout(30)
    void idempotentDuplicateSubmitDoesNotReexecute() throws Exception {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> submitArgs = Map.of(
                "task_id", taskId.toString(),
                "prompt", "idempotency test prompt",
                "forced_topology", "CHAIN"
        );

        McpSchema.CallToolResult first = client().callTool(
                new McpSchema.CallToolRequest("submit_task", submitArgs));
        JsonNode firstNode = parseToolResult(first);
        assertThat(firstNode.get("task_id").asText()).isEqualTo(taskId.toString());

        McpSchema.CallToolResult second = client().callTool(
                new McpSchema.CallToolRequest("submit_task", submitArgs));
        JsonNode secondNode = parseToolResult(second);
        assertThat(secondNode.get("task_id").asText()).isEqualTo(taskId.toString());

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tasks WHERE task_id = ?", Integer.class, taskId);
        assertThat(rowCount).isEqualTo(1);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            McpSchema.CallToolResult getResult = client().callTool(
                    new McpSchema.CallToolRequest("get_task", Map.of("task_id", taskId.toString())));
            JsonNode state = parseToolResult(getResult);
            if ("SUCCEEDED".equals(state.get("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
    void coexistenceAndAuthExemption() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest mcpGet = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/mcp"))
                .GET()
                .build();
        HttpResponse<String> mcpGetResponse = httpClient.send(mcpGet, HttpResponse.BodyHandlers.ofString());
        assertThat(mcpGetResponse.statusCode()).isNotEqualTo(401);

        HttpRequest tasksPost = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"foo\"}"))
                .build();
        HttpResponse<String> tasksPostResponse = httpClient.send(tasksPost, HttpResponse.BodyHandlers.ofString());
        assertThat(tasksPostResponse.statusCode()).isEqualTo(401);

        UUID taskId = UUID.randomUUID();
        McpSchema.CallToolResult submitResult = client().callTool(
                new McpSchema.CallToolRequest("submit_task", Map.of(
                        "task_id", taskId.toString(),
                        "prompt", "auth test prompt",
                        "forced_topology", "CHAIN")));
        JsonNode submitNode = parseToolResult(submitResult);
        assertThat(submitNode.get("task_id").asText()).isEqualTo(taskId.toString());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            McpSchema.CallToolResult getResult = client().callTool(
                    new McpSchema.CallToolRequest("get_task", Map.of("task_id", taskId.toString())));
            JsonNode state = parseToolResult(getResult);
            if ("SUCCEEDED".equals(state.get("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
    void unknownTaskIdReturnsToolErrorResult() {
        UUID unknownId = UUID.randomUUID();

        // get_task on unknown task_id
        McpSchema.CallToolResult getResult = client().callTool(
                new McpSchema.CallToolRequest("get_task", Map.of("task_id", unknownId.toString())));
        String errorText = extractErrorText(getResult);
        assertThat(errorText).contains("unknown task_id " + unknownId);

        // get_task_result on unknown task_id
        McpSchema.CallToolResult resultOutcome = client().callTool(
                new McpSchema.CallToolRequest("get_task_result", Map.of("task_id", unknownId.toString())));
        String resultErrorText = extractErrorText(resultOutcome);
        assertThat(resultErrorText).contains("unknown task_id " + unknownId);
    }

    @Test
    @Timeout(30)
    void invalidArgumentsYieldToolErrorResult() {
        // Breadth hint out of bounds (9)
        McpSchema.CallToolResult breadthResult = client().callTool(
                new McpSchema.CallToolRequest("submit_task", Map.of(
                        "task_id", UUID.randomUUID().toString(),
                        "prompt", "breadth hint check",
                        "breadth_hint", 9
                )));
        String breadthError = extractErrorText(breadthResult);
        assertThat(breadthError).contains("breadth_hint must be between 1 and 5");

        // Blank prompt
        McpSchema.CallToolResult blankPromptResult = client().callTool(
                new McpSchema.CallToolRequest("submit_task", Map.of(
                        "task_id", UUID.randomUUID().toString(),
                        "prompt", "   "
                )));
        String blankPromptError = extractErrorText(blankPromptResult);
        assertThat(blankPromptError).contains("prompt must not be blank");

        // Invalid forced_topology enum value
        McpSchema.CallToolResult invalidEnumResult = client().callTool(
                new McpSchema.CallToolRequest("submit_task", Map.of(
                        "task_id", UUID.randomUUID().toString(),
                        "prompt", "enum check",
                        "forced_topology", "NON_EXISTENT_TOPOLOGY"
                )));
        assertThat(invalidEnumResult.isError()).isTrue();
    }
}
