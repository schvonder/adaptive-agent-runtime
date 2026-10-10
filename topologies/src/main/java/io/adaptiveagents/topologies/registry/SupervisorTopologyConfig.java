package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.workload.tools.SearchTool;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the SUPERVISOR_TEAM topology factory. The {@link ChatClient.Builder} is taken from an
 * {@link ObjectProvider} so the bean still registers (with a null builder) in contexts that have no
 * chat model configured — the graph only fails if {@code graph()} is actually invoked there.
 */
@Configuration
public class SupervisorTopologyConfig {

    @Bean
    SupervisorGraphFactory supervisorGraphFactory(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            SearchTool searchTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider) {
        return new SupervisorGraphFactory(chatClientBuilder.getIfAvailable(), searchTool, nodeGuard, costTracker, tracerProvider);
    }
}
