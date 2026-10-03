package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.workload.tools.CiteTool;
import io.adaptiveagents.workload.tools.SearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the CHAIN topology factory. The {@link ChatClient.Builder} is taken from an
 * {@link ObjectProvider} so the bean still registers (with a null builder) in contexts that have no
 * chat model configured — the graph only fails if {@code build()} is actually invoked there.
 */
@Configuration
public class ChainTopologyConfig {

    @Bean
    ChainGraphFactory chainGraphFactory(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            SearchTool searchTool,
            CiteTool citeTool,
            NodeGuard nodeGuard) {
        return new ChainGraphFactory(chatClientBuilder.getIfAvailable(), searchTool, citeTool, nodeGuard);
    }
}
