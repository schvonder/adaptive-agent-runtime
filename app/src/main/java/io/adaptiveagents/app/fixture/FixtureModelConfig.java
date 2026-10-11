package io.adaptiveagents.app.fixture;

import io.adaptiveagents.core.classify.ComplexityClassifier;
import io.adaptiveagents.core.classify.RuleScorer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Test-profile bean set (same pattern as core's {@code stub-classifier} and app's {@code harness}
 * profile components): a deterministic, never-networking chat model for spawned-app e2e runs.
 * Activation is profile/property driven only — {@code fixture-model} plus
 * {@code runtime.fixture-model.delay-ms} (per-call sleep, e.g. 3000 for a 3s/node CHAIN) and
 * {@code runtime.fixture-model.response-text}.
 *
 * <p>The profile is also the hermetic gate for the docker-compose demo stack (T29): without a
 * configured model endpoint no {@code ChatClient.Builder} exists, so {@code LlmClassifier}
 * backs off entirely and this config supplies the rules-only classifier instead — every verdict
 * comes straight from {@link RuleScorer}, which is exactly the fallback path
 * {@code LlmClassifier} itself takes when the LLM leg is unavailable.
 */
@Configuration
@Profile("fixture-model")
public class FixtureModelConfig {

    @Bean
    @Primary
    ChatModel fixtureChatModel(
            @Value("${runtime.fixture-model.delay-ms:0}") long delayMs,
            @Value("${runtime.fixture-model.response-text:adaptive agents}") String responseText) {
        return new FixedTextChatModel(responseText, delayMs);
    }

    @Bean
    @Primary
    ComplexityClassifier fixtureClassifier(RuleScorer ruleScorer) {
        return (envelope, nowMs) -> ruleScorer.score(envelope, nowMs);
    }
}
