package io.adaptiveagents.core.classify;

import io.adaptiveagents.core.envelope.TaskEnvelope;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * LLM leg of the complexity classifier, consulted only for the AMBIGUOUS rule band.
 *
 * <p>Model selection: the {@code runtime.classifier.model} property overrides the model for
 * this leg (e.g. pinning a cheaper classifier model than the agent-facing one). When the
 * property is blank the request carries no per-call model and Spring AI resolves the global
 * default ({@code spring.ai.openai.chat.options.model}) — the same model every topology node
 * uses.</p>
 */
@Component
@ConditionalOnBean(ChatClient.Builder.class)
public class LlmClassifier implements ComplexityClassifier {

    private final ChatClient chatClient;
    private final RuleScorer ruleScorer;
    private final String model;

    public LlmClassifier(
            ChatClient.Builder chatClientBuilder,
            RuleScorer ruleScorer,
            @Value("${runtime.classifier.model:}") String model) {
        this.chatClient = chatClientBuilder.build();
        this.ruleScorer = ruleScorer;
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public ComplexityVerdict classify(TaskEnvelope envelope, long nowMs) {
        ComplexityVerdict ruleVerdict = ruleScorer.score(envelope, nowMs);
        if (ruleVerdict.band() != Band.AMBIGUOUS) {
            return ruleVerdict;
        }

        try {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().temperature(0.0);
            if (!model.isBlank()) {
                options = options.model(model);
            }
            LlmVerdict llmResult = chatClient.prompt()
                    .user(u -> u.text("Evaluate complexity for prompt: {prompt}. Breadth hint: {breadth}")
                            .param("prompt", envelope.prompt())
                            .param("breadth", envelope.breadthHint()))
                    .options(options)
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
