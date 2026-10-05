package io.adaptiveagents.app.fixture;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Fixture {@link ChatModel} for the {@code fixture-model} profile: every call answers with the
 * same fixed text after an optional per-call delay. The delay is what makes spawned-app e2e tests
 * killable mid-node (a "long CHAIN task" without any live model).
 */
final class FixedTextChatModel implements ChatModel {

    private final String responseText;
    private final long delayMs;

    FixedTextChatModel(String responseText, long delayMs) {
        this.responseText = responseText;
        this.delayMs = delayMs;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
    }
}
