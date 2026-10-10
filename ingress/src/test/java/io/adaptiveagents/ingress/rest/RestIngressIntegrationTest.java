package io.adaptiveagents.ingress.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * REST ingress integration test (T21): Testcontainers Postgres + MockMvc against the full
 * io.adaptiveagents component scan, mirroring the ExecutionRunnerIntegrationTest wiring (inner
 * Config, stub-classifier profile, @Primary model/search doubles, per-test table reset).
 * Doubles live INLINE because ingress has no access to topologies test-jar classes.
 *
 * <p>Covers: X-API-Key auth (401 problem+json), submit lifecycle with idempotent dedup
 * (202/200), SSE ordered replay incl. Last-Event-ID reconnect, invalid topology/task_type 400
 * problem+json, and unknown task 404 problem+json. SSE assertions are made on the collected
 * frame list after the emitter completes on the terminal event — deterministic, no sleeps
 * beyond polling the DB first.
 */
@SpringBootTest(classes = RestIngressIntegrationTest.Config.class, properties = {
        "spring.ai.model.chat=none",
        "runtime.api-key=test-key"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class RestIngressIntegrationTest {

    private static final String API_KEY = "test-key";
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

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
            return new FixedTextChatModel("adaptive agents runtime research summary");
        }

        @Bean
        @Primary
        SearchTool searchTool() {
            return query -> List.of(new SearchResult(
                    "Canned Result", "https://example.com/result", "A canned snippet."));
        }
    }

    /** Minimal scripted ChatModel: every call repeats one fixed text response. */
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
    WebApplicationContext webApplicationContext;

    @Autowired
    ApiKeyAuthFilter apiKeyAuthFilter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    MockMvc mockMvc;

    @BeforeEach
    void reset() {
        // Boot 4 moved @AutoConfigureMockMvc to a dedicated starter not on this module's
        // dependency list; building MockMvc manually exercises the same surfaces — the real
        // context (controllers, advice) plus the real auth filter bean in the chain.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(apiKeyAuthFilter)
                .build();
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        // Saver tables are lazily created by PostgresSaver on first compile; drop-if-exists so
        // this is idempotent on a fresh container. They are recreated by createTables(true).
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    @Test
    @Timeout(60)
    void authMissingOrWrongKeyIsRejected() throws Exception {
        mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(chainEnvelope(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "wrong-key").content(chainEnvelope(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        mockMvc.perform(get("/tasks/{id}/events", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    @Timeout(60)
    void submitLifecycleWithDedupAndUnknownTask404() throws Exception {
        UUID taskId = UUID.randomUUID();
        String body = chainEnvelope(taskId);

        String created = mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY).content(body))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.task_id").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value(
                        org.hamcrest.Matchers.isOneOf("ROUTED", "RUNNING", "SUCCEEDED")))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).isNotBlank();

        // Immediate duplicate: 200 with the SAME task_id, no re-route, no re-execution.
        mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value(taskId.toString()));

        awaitStatus(taskId, "SUCCEEDED");

        mockMvc.perform(get("/tasks/{id}", taskId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.topology").value("CHAIN"))
                .andExpect(jsonPath("$.result").isNotEmpty())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/tasks/{id}", UUID.randomUUID()).header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    @Timeout(60)
    void sseOrderedWithReplay() throws Exception {
        UUID taskId = UUID.randomUUID();
        mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY).content(chainEnvelope(taskId)))
                .andExpect(status().isAccepted());
        awaitStatus(taskId, "SUCCEEDED");

        List<Frame> frames = streamEvents(taskId, null);
        assertThat(frames).hasSize(5);
        assertThat(frames.stream().map(Frame::event).toList())
                .containsExactly("NODE_START", "NODE_START", "NODE_START", "NODE_START", "TASK_COMPLETED");
        assertThat(frames.stream().map(this::nodeName).toList())
                .containsExactly("plan", "search", "summarize", "cite", null);
        for (int i = 1; i < frames.size(); i++) {
            assertThat(frames.get(i).id()).isGreaterThan(frames.get(i - 1).id());
        }

        // Reconnect from the search NODE_START's seq: exactly the events AFTER it replay, in
        // order, ending on the terminal event (the core acceptance).
        long searchSeq = frames.get(1).id();
        List<Frame> replay = streamEvents(taskId, searchSeq);
        assertThat(replay.stream().map(Frame::event).toList())
                .containsExactly("NODE_START", "NODE_START", "TASK_COMPLETED");
        assertThat(replay.stream().map(this::nodeName).toList())
                .containsExactly("summarize", "cite", null);
        assertThat(replay.get(0).id()).isGreaterThan(searchSeq);
    }

    @Test
    @Timeout(60)
    void invalidTopologyAndTaskTypeAre400() throws Exception {
        String bogusTopology = """
                {"task_id":"%s","task_type":"research","prompt":"research adaptive agents","forced_topology":"BOGUS"}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY).content(bogusTopology))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        String badType = """
                {"task_id":"%s","task_type":"summarize","prompt":"summarize this"}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY).content(badType))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    @Timeout(60)
    void sseUnknownTaskIs404() throws Exception {
        mockMvc.perform(get("/tasks/{id}/events", UUID.randomUUID()).header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    // --- helpers -------------------------------------------------------------

    private static String chainEnvelope(UUID taskId) {
        return """
                {"task_id":"%s","task_type":"research","prompt":"research adaptive agents","breadth_hint":1,"forced_topology":"CHAIN"}
                """.formatted(taskId);
    }

    private void awaitStatus(UUID taskId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM tasks WHERE task_id = ?", String.class, taskId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("task " + taskId + " never reached " + expected);
    }

    /** Opens the SSE endpoint; returns the collected frames once the emitter completed. */
    private List<Frame> streamEvents(UUID taskId, Long lastEventId) throws Exception {
        MockHttpServletRequestBuilder request = get("/tasks/{id}/events", taskId)
                .header("X-API-Key", API_KEY)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request = request.header("Last-Event-ID", Long.toString(lastEventId));
        }
        MvcResult started = mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(10_000);
        MvcResult dispatched = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return parseFrames(body);
    }

    private List<Frame> parseFrames(String body) throws Exception {
        List<Frame> frames = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            long id = -1;
            String event = null;
            String data = "";
            for (String line : block.split("\n")) {
                if (line.startsWith("id:")) {
                    id = Long.parseLong(line.substring(3).trim());
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).trim();
                }
            }
            frames.add(new Frame(id, event, data));
        }
        return frames;
    }

    private String nodeName(Frame frame) {
        try {
            JsonNode node = objectMapper.readTree(frame.data()).path("node");
            return node.isMissingNode() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    record Frame(long id, String event, String data) {}
}
