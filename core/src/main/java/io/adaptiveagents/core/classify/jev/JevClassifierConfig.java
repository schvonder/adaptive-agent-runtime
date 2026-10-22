package io.adaptiveagents.core.classify.jev;

import io.adaptiveagents.core.classify.ComplexityClassifier;
import io.adaptiveagents.core.classify.RuleScorer;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Jev (TypeSafe AI) classification leg when {@code runtime.classifier.leg=jev}. The
 * default leg stays llm ({@link io.adaptiveagents.core.classify.LlmClassifier}), so exactly one
 * {@link ComplexityClassifier} bean exists per mode — RoutingService injects it directly.
 *
 * <p>Properties (defaults in the {@code @Value} placeholders): {@code runtime.classifier.jev.base-url},
 * {@code runtime.classifier.jev.api-key}, {@code runtime.classifier.jev.model},
 * {@code runtime.classifier.jev.confidence-threshold},
 * {@code runtime.classifier.jev.injection-threshold}, and the shared
 * {@code runtime.classifier.rationale-mode}. rationale-mode=llm additionally requires a
 * {@link ChatClient.Builder} on the context for the narrator; without one the context fails to
 * start with an explicit message rather than silently degrading the rationale mode.</p>
 *
 * <p>The injection threshold defaults to 0.5 deliberately: noul probabilities are poorly
 * calibrated around exactly 0.5 per the independent eval, but the cost of a false positive is
 * only the deterministic rule fallback — never a wrong topology — so the default stays
 * permissive rather than over-trusting the decision leg.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "runtime.classifier.leg", havingValue = "jev")
public class JevClassifierConfig {

    @Bean
    public TypeSafeClient typeSafeClient(
            @Value("${runtime.classifier.jev.base-url:https://api.typesafe.ai}") String baseUrl,
            @Value("${runtime.classifier.jev.api-key:}") String apiKey,
            @Value("${runtime.classifier.jev.model:jev-latest}") String model) {
        return TypeSafeClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .defaultModel(model)
                .build();
    }

    @Bean
    public TypeSafeJevDecider typeSafeJevDecider(
            TypeSafeClient typeSafeClient,
            @Value("${runtime.classifier.rationale-mode:codes}") String rationaleMode) {
        return new TypeSafeJevDecider(typeSafeClient, "codes".equals(JevClassifier.normalizeRationaleMode(rationaleMode)));
    }

    @Bean
    public ComplexityClassifier jevClassifier(
            RuleScorer ruleScorer,
            JevTopologyDecider jevTopologyDecider,
            @Value("${runtime.classifier.jev.confidence-threshold:0.6}") double confidenceThreshold,
            @Value("${runtime.classifier.jev.injection-threshold:0.5}") double injectionThreshold,
            @Value("${runtime.classifier.rationale-mode:codes}") String rationaleMode,
            ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        String mode = JevClassifier.normalizeRationaleMode(rationaleMode);
        RationaleNarrator narrator = null;
        if ("llm".equals(mode)) {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                throw new IllegalStateException(
                        "runtime.classifier.rationale-mode=llm requires a ChatClient.Builder on the context "
                                + "(a Spring AI chat model) to build the Jev rationale narrator");
            }
            narrator = new LlmRationaleNarrator(builder.build());
        }
        return new JevClassifier(ruleScorer, jevTopologyDecider, confidenceThreshold, injectionThreshold, mode, narrator);
    }
}
