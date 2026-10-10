package io.adaptiveagents.ingress.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link McpTaskTools} as the server's MCP tools. The
 * {@code spring-ai-starter-mcp-server-webmvc} auto-configuration (STREAMABLE protocol) picks
 * up every {@link ToolCallbackProvider} bean and exposes the tools at {@code /mcp} on the
 * SAME port as REST/SSE. {@code expose-mcp-client-tools} stays false (default) and the app
 * ships no MCP client — there is nothing to federate, by design.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider taskTools(McpTaskTools taskTools) {
        return MethodToolCallbackProvider.builder().toolObjects(taskTools).build();
    }
}
