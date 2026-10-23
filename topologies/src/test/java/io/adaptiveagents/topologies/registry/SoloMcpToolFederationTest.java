package io.adaptiveagents.topologies.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.guard.NoOpGuard;
import io.adaptiveagents.topologies.solo.SoloState;
import io.adaptiveagents.workload.tools.CiteTool;
import io.adaptiveagents.workload.tools.ResearchTools;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Proves MCP-federated tool callbacks reach the model call: the SOLO graph is built with a stub
 * {@link ToolCallbackProvider} (the bean the spring-ai MCP client starter registers) and run
 * against a recording {@link ChatModel}; the captured prompt's options must carry the stub
 * callback. Hermetic — no Spring context, no network, the stub never dials an MCP server.
 */
class SoloMcpToolFederationTest {

    private static final String STUB_TOOL_NAME = "stub_mcp_tool";

    static final class StubToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(STUB_TOOL_NAME)
                    .description("stub MCP-federated tool")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "stub-tool-output: " + toolInput;
        }
    }

    static final class RecordingChatModel implements ChatModel {

        private final String responseText;
        private Prompt capturedPrompt;

        RecordingChatModel(String responseText) {
            this.responseText = responseText;
        }

        /**
         * Real chat models (e.g. OpenAI) expose tool-calling options; DefaultChatClient only
         * merges spec-level tool callbacks into the prompt options when the model options are
         * {@link ToolCallingChatOptions}.
         */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.capturedPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
        }
    }

    @Test
    void federatedToolCallbackReachesTheModelCall() throws Exception {
        StubToolCallback stubTool = new StubToolCallback();
        RecordingChatModel model = new RecordingChatModel("adaptive agents");

        SoloGraphFactory factory = new SoloGraphFactory(
                ChatClient.builder(model),
                new ResearchTools(query -> List.of(), url -> "", new CiteTool()),
                new NoOpGuard(),
                // Usable without a store: the fixed-text responses carry no usage metadata,
                // so record() skips persistence before touching the template.
                new CostTracker(null),
                stubProvider(stubTool));

        SoloState state = runGraph(factory);

        assertThat(state.result()).isEqualTo("adaptive agents");
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.capturedPrompt.getOptions();
        List<ToolCallback> federated = options.getToolCallbacks().stream()
                .filter(callback -> callback.getToolDefinition().name().equals(STUB_TOOL_NAME))
                .toList();
        assertThat(federated).hasSize(1);
        assertThat(federated.get(0)).isSameAs(stubTool);
        assertThat(stubTool.call("{}")).isEqualTo("stub-tool-output: {}");
        // The workload tool surface stays attached alongside the federated tool.
        assertThat(options.getToolCallbacks())
                .anySatisfy(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("web_search"));
    }

    @Test
    void absentProviderLeavesNoFederatedToolCallbacksInTheModelCall() throws Exception {
        RecordingChatModel model = new RecordingChatModel("adaptive agents");

        SoloGraphFactory factory = new SoloGraphFactory(
                ChatClient.builder(model),
                new ResearchTools(query -> List.of(), url -> "", new CiteTool()),
                new NoOpGuard(),
                new CostTracker(null));

        SoloState state = runGraph(factory);

        assertThat(state.result()).isEqualTo("adaptive agents");
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.capturedPrompt.getOptions();
        assertThat(options.getToolCallbacks())
                .noneSatisfy(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo(STUB_TOOL_NAME));
    }

    private static ObjectProvider<ToolCallbackProvider> stubProvider(ToolCallback stubTool) {
        return new ObjectProvider<>() {
            @Override
            public ToolCallbackProvider getIfAvailable() {
                return () -> new ToolCallback[] {stubTool};
            }
        };
    }

    private static SoloState runGraph(SoloGraphFactory factory) throws Exception {
        CompiledGraph<SoloState> graph = factory.graph().compile();
        RunnableConfig config = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
        return graph.invoke(Map.of(SoloState.PROMPT, "research adaptive agents"), config).orElseThrow();
    }
}
