package io.adaptiveagents.spike;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.StateGraph.START;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(classes = WireMockSpringAiTest.Config.class)
class WireMockSpringAiTest {

    static WireMockServer wireMockServer;

    @BeforeAll
    static void setup() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8089));
        wireMockServer.start();
    }

    @AfterAll
    static void teardown() {
        wireMockServer.stop();
    }

    @SpringBootApplication
    static class Config {
        @org.springframework.context.annotation.Bean
        public WeatherService weatherService() {
            return new WeatherService();
        }
    }

    static class WeatherService {
        @org.springframework.ai.tool.annotation.Tool(description = "Get weather")
        public String getWeather(WeatherRequest request) {
            return "72F in " + request.location();
        }
    }

    record WeatherRequest(String location) {}

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "sk-mock-key");
        registry.add("spring.ai.openai.base-url", () -> "http://localhost:8089");
    }

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void testChatClientWithWireMockAndLangGraph() throws Exception {
        wireMockServer.stubFor(post(anyUrl())
            .inScenario("Chat")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                {
                  "id": "chatcmpl-123",
                  "object": "chat.completion",
                  "created": 1677652288,
                  "model": "gpt-4o-mock",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                              "name": "getWeather",
                              "arguments": "{\\"location\\":\\"San Francisco\\"}"
                            }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ]
                }
                """))
            .willSetStateTo("Tool Called"));

        wireMockServer.stubFor(post(anyUrl())
            .inScenario("Chat")
            .whenScenarioStateIs("Tool Called")
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                {
                  "id": "chatcmpl-456",
                  "object": "chat.completion",
                  "created": 1677652290,
                  "model": "gpt-4o-mock",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "The weather in San Francisco is 72F."
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """)));

        ChatClient chatClient = chatClientBuilder.build();

        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        
        NodeAction<AgentState> action = state -> {
            String response = chatClient.prompt().user("What's the weather?").tools(new WeatherService()).call().content();
            return Map.of("final_answer", response);
        };
        
        graph.addNode("agent", node_async(action));

        graph.addEdge(START, "agent");
        graph.addEdge("agent", org.bsc.langgraph4j.StateGraph.END);

        CompiledGraph<AgentState> compiled = graph.compile();
        Optional<AgentState> resultOpt = compiled.invoke(Map.of());
        
        assertThat(resultOpt).isPresent();
        AgentState result = resultOpt.get();

        assertThat(result.data()).containsEntry("final_answer", "The weather in San Francisco is 72F.");
    }
}
