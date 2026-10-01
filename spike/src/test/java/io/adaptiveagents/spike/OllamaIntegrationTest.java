package io.adaptiveagents.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OllamaIntegrationTest.Config.class)
@EnabledIfEnvironmentVariable(named = "OLLAMA_BASE_URL", matches = ".+")
class OllamaIntegrationTest {

    @SpringBootApplication
    static class Config {
    }

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void testLocalOllamaEndpoint() {
        ChatClient chatClient = chatClientBuilder.build();
        
        String response = chatClient.prompt()
            .user("Respond with the word 'HELLO'")
            .call()
            .content();
            
        assertThat(response).containsIgnoringCase("HELLO");
    }
}
