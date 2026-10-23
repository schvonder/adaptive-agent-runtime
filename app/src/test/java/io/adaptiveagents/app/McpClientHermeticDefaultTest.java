package io.adaptiveagents.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Hermetic-default contract of the MCP client starter: with no {@code spring.ai.mcp.client.*}
 * configuration the context boots unchanged and zero federated tool callbacks are exposed to the
 * topology graphs. (Verified against spring-ai-autoconfigure-mcp-client-common 2.0.1: an empty
 * {@code SyncMcpToolCallbackProvider} bean may exist — with no configured connections it opens no
 * sessions and supplies no callbacks.)
 */
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "spring.ai.model.chat=none",
    "spring.datasource.url=jdbc:postgresql://localhost:5432/postgres",
    "spring.datasource.username=postgres",
    "spring.datasource.password=postgres",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    // DB-less context smoke: without this the flyway initializer eagerly opens a connection.
    "spring.flyway.enabled=false"
})
@ActiveProfiles({"stub-classifier", "fixture-model"})
class McpClientHermeticDefaultTest {

    @Autowired
    @Qualifier("mcpToolCallbacks")
    ObjectProvider<ToolCallbackProvider> toolCallbackProvider;

    @Test
    void noMcpClientConfigExposesNoFederatedTools() {
        ToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
        if (provider == null) {
            // Acceptable hermetic state: no provider bean at all.
            return;
        }
        assertThat(provider.getToolCallbacks())
                .as("starter registers zero MCP client connections, so no federated tools may leak into the graphs")
                .isEmpty();
    }
}
