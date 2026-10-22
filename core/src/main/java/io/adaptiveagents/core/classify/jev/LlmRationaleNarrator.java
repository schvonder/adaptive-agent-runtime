package io.adaptiveagents.core.classify.jev;

import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;

/**
 * Rationale narrator backed by a chat model: one prompt, one sentence. The decision (band,
 * topology) is already fixed by Jev when this runs, so the model cannot change routing — it only
 * phrases why.
 */
public class LlmRationaleNarrator implements RationaleNarrator {

    private final ChatClient chatClient;

    public LlmRationaleNarrator(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
    }

    @Override
    public String narrate(String state, JevDecision decision) {
        String content = chatClient.prompt()
                .user(u -> u.text("Given the task and the decision below, write a ONE-sentence rationale. "
                        + "Decision: band={band}, topology={topology}. Task: {task}")
                        .param("band", decision.band())
                        .param("topology", decision.topology())
                        .param("task", state))
                .call()
                .content();
        return content == null ? "" : content.trim();
    }
}
