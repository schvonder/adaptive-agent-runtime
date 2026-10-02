package io.adaptiveagents.core.classify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;

@SpringBootTest(classes = LlmClassifierWireMockTest.Config.class)
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.ai.openai.api-key=sk-mock-key",
    "spring.ai.openai.base-url=http://localhost:8089"
})
public class LlmClassifierWireMockTest {

    static WireMockServer wireMockServer;
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void setup() {
        postgres.start();
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8089));
        wireMockServer.start();
    }

    @AfterAll
    static void teardown() {
        wireMockServer.stop();
        postgres.stop();
    }

    @BeforeEach
    void reset() {
        wireMockServer.resetAll();
    }

    @SpringBootApplication(scanBasePackages = "io.adaptiveagents.core.classify")
    static class Config {
        @Bean
        public org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder(org.springframework.ai.chat.model.ChatModel chatModel) {
            return org.springframework.ai.chat.client.ChatClient.builder(chatModel);
        }
        @Bean
        public DataSource dataSource() {
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            return new com.zaxxer.hikari.HikariDataSource(config);
        }
    }

    @Autowired
    private LlmClassifier classifier;

    private TaskEnvelope createEnvelope(int breadth, int promptLength) {
        return new TaskEnvelope(UUID.randomUUID(), "research", "a".repeat(promptLength), breadth, null, null, null);
    }

    @Test
    void ambiguousValidSchema() {
        // AMBIGUOUS score: breadth=2, prompt length=400 (100 tokens, score 0, breadth +5 -> score 5 -> AMBIGUOUS)
        TaskEnvelope env = createEnvelope(2, 400);

        wireMockServer.stubFor(post(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "id": "chatcmpl-123",
                          "object": "chat.completion",
                          "created": 1677652288,
                          "model": "gpt-4o-mini",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "{\\"band\\":\\"AMBIGUOUS\\",\\"topology\\":\\"FAN_OUT\\",\\"rationale\\":\\"LLM thinks so\\"}"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
                        }
                        """)));

        ComplexityVerdict verdict = classifier.classify(env, 1000L);

        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.FAN_OUT);
        assertThat(verdict.rationale()).isEqualTo("LLM thinks so");

        wireMockServer.verify(1, postRequestedFor(anyUrl()));
    }

    @Test
    void ambiguousMalformedOutput() {
        TaskEnvelope env = createEnvelope(2, 400);

        wireMockServer.stubFor(post(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "id": "chatcmpl-123",
                          "object": "chat.completion",
                          "created": 1677652288,
                          "model": "gpt-4o-mini",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "{\\"band\\":\\"AMBIGUOUS\\",\\"topology\\":\\"TOPOLOGY_TEAM\\",\\"rationale\\":\\"bad enum\\"}"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
                        }
                        """)));

        ComplexityVerdict verdict = classifier.classify(env, 1000L);

        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.CHAIN); // rule scorer default
        assertThat(verdict.rationale()).contains("[llm-fallback: ");
        assertThat(verdict.rationale()).contains("Breadth 2");

        wireMockServer.verify(1, postRequestedFor(anyUrl()));
    }

    @Test
    void clearBandsNeverCallLlm() {
        // CLEAR_SIMPLE: tiny prompt (40 chars -> 10 tokens, -10), breadth 1 (-10) => -20
        TaskEnvelope envSimple = createEnvelope(1, 40);
        ComplexityVerdict verdictSimple = classifier.classify(envSimple, 1000L);
        
        assertThat(verdictSimple.band()).isEqualTo(Band.CLEAR_SIMPLE);
        assertThat(verdictSimple.candidateTopology()).isEqualTo(Topology.SOLO);

        // CLEAR_COMPLEX: breadth 5 (+20), normal prompt -> 20
        TaskEnvelope envComplex = createEnvelope(5, 400);
        ComplexityVerdict verdictComplex = classifier.classify(envComplex, 1000L);
        
        assertThat(verdictComplex.band()).isEqualTo(Band.CLEAR_COMPLEX);
        assertThat(verdictComplex.candidateTopology()).isEqualTo(Topology.SUPERVISOR);

        wireMockServer.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void endpoint500() {
        TaskEnvelope env = createEnvelope(2, 400);

        wireMockServer.stubFor(post(anyUrl())
                .willReturn(aResponse().withStatus(500)));

        ComplexityVerdict verdict = classifier.classify(env, 1000L);

        assertThat(verdict.band()).isEqualTo(Band.AMBIGUOUS);
        assertThat(verdict.candidateTopology()).isEqualTo(Topology.CHAIN);
        assertThat(verdict.rationale()).contains("[llm-fallback: ");
        assertThat(verdict.rationale()).contains("500");

        wireMockServer.verify(moreThanOrExactly(1), postRequestedFor(anyUrl()));
    }
}
