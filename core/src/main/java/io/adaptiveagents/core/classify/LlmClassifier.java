package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ChatClient.Builder.class)
public class LlmClassifier implements ComplexityClassifier {

    private final ChatClient chatClient;
    private final RuleScorer ruleScorer;
    private final String model;

    public LlmClassifier(ChatClient.Builder chatClientBuilder, RuleScorer ruleScorer) {
        this.chatClient = chatClientBuilder.build();
        this.ruleScorer = ruleScorer;
        this.model = "gpt-4o-mini";
    }

    @Override
    public ComplexityVerdict classify(TaskEnvelope envelope, long nowMs) {
        ComplexityVerdict ruleVerdict = ruleScorer.score(envelope, nowMs);
        if (ruleVerdict.band() != Band.AMBIGUOUS) {
            return ruleVerdict;
        }

        try {
            LlmVerdict llmResult = chatClient.prompt()
                    .user(u -> u.text("Evaluate complexity for prompt: {prompt}. Breadth hint: {breadth}")
                            .param("prompt", envelope.prompt())
                            .param("breadth", envelope.breadthHint()))
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(0.0))
                    .call()
                    .entity(LlmVerdict.class);

            if (llmResult == null || llmResult.band() != Band.AMBIGUOUS || llmResult.topology() == null) {
                throw new IllegalStateException("Invalid LLM response: " + llmResult);
            }

            return new ComplexityVerdict(ruleVerdict.score(), llmResult.band(), llmResult.topology(), llmResult.rationale());
        } catch (Exception e) {
            String fallbackRationale = "[llm-fallback: " + e.getMessage() + "] " + ruleVerdict.rationale();
            return new ComplexityVerdict(ruleVerdict.score(), ruleVerdict.band(), ruleVerdict.candidateTopology(), fallbackRationale);
        }
    }
}
