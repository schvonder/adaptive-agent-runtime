package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.workload.tools.ResearchTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the SOLO topology factory. The {@link ChatClient.Builder} is taken from an
 * {@link ObjectProvider} so the bean still registers (with a null builder) in contexts that have no
 * chat model configured — the graph only fails if {@code graph()} is actually invoked there.
 */
@Configuration
public class SoloTopologyConfig {

    @Bean
    SoloGraphFactory soloGraphFactory(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            ResearchTools researchTools,
            NodeGuard nodeGuard) {
        return new SoloGraphFactory(chatClientBuilder.getIfAvailable(), researchTools, nodeGuard);
    }
}
