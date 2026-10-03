package io.adaptiveagents.topologies.testutil;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ChatModel test double that drains a scripted queue of responses in order. The last entry repeats
 * for open-ended scripts, so a single entry yields an "always the same" model. A scripted
 * {@link RuntimeException} makes {@link #call(Prompt)} throw at that position.
 */
public class ScriptedChatModel implements ChatModel {

    private final List<Object> script = new ArrayList<>();
    private int cursor = 0;

    public void enqueue(ChatResponse... responses) {
        script.addAll(List.of(responses));
    }

    public void enqueueThrow(RuntimeException error) {
        script.add(error);
    }

    public void reset() {
        script.clear();
        cursor = 0;
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        if (script.isEmpty()) {
            throw new IllegalStateException("ScriptedChatModel has no scripted responses");
        }
        Object entry = script.get(Math.min(cursor, script.size() - 1));
        cursor++;
        if (entry instanceof RuntimeException error) {
            throw error;
        }
        return (ChatResponse) entry;
    }

    public static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    public static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
