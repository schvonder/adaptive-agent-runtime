package io.adaptiveagents.topologies.registry;

import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.workload.tools.SearchTool;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the FAN_OUT topology factory. The {@link ChatClient.Builder} is taken from an
 * {@link ObjectProvider} so the bean still registers (with a null builder) in contexts that have no
 * chat model configured — the graph only fails if {@code graph()} is actually invoked there. The
 * optional {@link ToolCallbackProvider} federates MCP-client tools into the graph when present.
 */
@Configuration
public class FanOutTopologyConfig {

    @Bean
    FanOutGraphFactory fanOutGraphFactory(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            SearchTool searchTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider,
            // By name — the context also has the server-side "taskTools" provider; null when no MCP client.
            @Qualifier("mcpToolCallbacks")
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        return new FanOutGraphFactory(chatClientBuilder.getIfAvailable(), searchTool, nodeGuard, costTracker, tracerProvider, toolCallbackProvider);
    }
}
