package io.adaptiveagents.topologies.registry;

import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.skills.SkillTools;
import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.guard.GuardException;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.topologies.solo.SoloState;
import io.adaptiveagents.workload.tools.ResearchTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SOLO topology: a single-node graph that runs a bounded ReAct loop (max {@value #MAX_ITERATIONS}
 * iterations) with the workload {@link ResearchTools} registered as Spring AI tools.
 *
 * <p>The node never rethrows past the graph: tool errors and loop exhaustion are folded into
 * {@link SoloState#ERROR} plus {@code degraded=true}, so a bad run degrades the state instead of
 * crashing the topology. A {@link GuardException} at the loop boundary is folded separately with the
 * {@link SoloState#GUARD_REJECTED} flag so the runner can map it to a terminal status; the tool
 * transcript of completed iterations is preserved as the {@link SoloState#PARTIAL_RESULT} snapshot
 * (SOLO degradation: stop).</p>
 */
public final class SoloGraphFactory implements GraphFactory {

    private static final Logger log = LoggerFactory.getLogger(SoloGraphFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String AGENT_NODE = "solo-agent";
    public static final int MAX_ITERATIONS = 3;

    private final ChatClient.Builder chatClientBuilder;
    private final ResearchTools researchTools;
    private final NodeGuard nodeGuard;
    private final CostTracker costTracker;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final SkillTools skillTools;

    /**
     * @param chatClientBuilder     Spring AI chat client builder; may be {@code null} in wiring-only
     *     contexts (the graph is unusable until {@link #graph()} is called with a real builder)
     * @param researchTools         workload tool surface registered with the model
     * @param nodeGuard             per-iteration budget/deadline seam
     * @param costTracker           per-response token/cost accounting
     * @param toolCallbackProvider  optional MCP-federated tool callbacks; may be {@code null} in
     *     wiring-only contexts (no federated tools are attached to the model calls)
     * @param skillTools            optional skills surface ({@code list_skills}/{@code load_skill});
     *     {@code null} when no skills folder is configured
     */
    public SoloGraphFactory(
            ChatClient.Builder chatClientBuilder,
            ResearchTools researchTools,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            SkillTools skillTools) {
        this.chatClientBuilder = chatClientBuilder;
        this.researchTools = researchTools;
        this.nodeGuard = nodeGuard;
        this.costTracker = costTracker;
        this.toolCallbackProvider = toolCallbackProvider;
        this.skillTools = skillTools;
    }

    public SoloGraphFactory(
            ChatClient.Builder chatClientBuilder,
            ResearchTools researchTools,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        this(chatClientBuilder, researchTools, nodeGuard, costTracker, toolCallbackProvider, null);
    }

    public SoloGraphFactory(ChatClient.Builder chatClientBuilder, ResearchTools researchTools, NodeGuard nodeGuard, CostTracker costTracker) {
        this(chatClientBuilder, researchTools, nodeGuard, costTracker, null);
    }

    @Override
    public StateGraph<SoloState> graph() {
        try {
            ChatClient chatClient = buildChatClient();
            List<ToolCallback> federatedTools = federatedToolCallbacks();
            StateGraph<SoloState> graph = new StateGraph<>(SoloState::new);
            graph.addNode(AGENT_NODE, node_async(agentAction(chatClient, federatedTools)));
            graph.addEdge(StateGraph.START, AGENT_NODE);
            graph.addEdge(AGENT_NODE, StateGraph.END);
            return graph;
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to assemble SOLO graph", e);
        }
    }

    private NodeActionWithConfig<SoloState> agentAction(ChatClient chatClient, List<ToolCallback> federatedTools) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            List<String> transcript = new ArrayList<>();
            String error = null;

            for (int i = 0; i < MAX_ITERATIONS; i++) {
                try {
                    nodeGuard.check(AGENT_NODE, taskId);
                } catch (GuardException e) {
                    log.warn("Guard rejected task {} at iteration {}: {}", taskId, i, e.getMessage());
                    return Map.of(
                            SoloState.DEGRADED, true,
                            SoloState.ERROR, "guard rejected: " + e.getMessage(),
                            SoloState.GUARD_REJECTED, e.getClass().getSimpleName(),
                            SoloState.TOOL_TRANSCRIPT, transcript,
                            SoloState.PARTIAL_RESULT, transcriptJson(transcript));
                }

                try {
                    ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                            .user(state.prompt())
                            .tools(researchTools);
                    if (skillTools != null) {
                        spec = spec.tools(skillTools);
                    }
                    if (!federatedTools.isEmpty()) {
                        spec = spec.toolCallbacks(federatedTools);
                    }
                    ChatResponse response = spec.call().chatResponse();
                    costTracker.record(AGENT_NODE, taskId, response);
                    String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
                    if (text != null && !text.isBlank()) {
                        transcript.add("iteration " + i + ": final answer");
                        return Map.of(SoloState.RESULT, text, SoloState.TOOL_TRANSCRIPT, transcript);
                    }
                    transcript.add("iteration " + i + ": empty model output");
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    transcript.add("iteration " + i + ": error " + error);
                    log.warn("SOLO agent iteration {} failed for task {}: {}", i, taskId, error);
                }
            }

            transcript.add("exhausted after " + MAX_ITERATIONS + " iterations");
            return Map.of(
                    SoloState.DEGRADED, true,
                    SoloState.ERROR, error != null ? error : "exceeded " + MAX_ITERATIONS + " iterations",
                    SoloState.TOOL_TRANSCRIPT, transcript);
        };
    }

    private static String transcriptJson(List<String> transcript) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode entries = root.putArray("toolTranscript");
        transcript.forEach(entries::add);
        return root.toString();
    }

    private ChatClient buildChatClient() {
        if (chatClientBuilder == null) {
            throw new IllegalStateException("ChatClient.Builder is not available; cannot build SOLO graph");
        }
        return chatClientBuilder.build();
    }

    /**
     * Resolves MCP-federated tool callbacks once per graph build. Absent provider, missing bean,
     * or empty callback list yield an empty list — the model call is then built exactly as before,
     * with no extra objects in the prompt path.
     */
    private List<ToolCallback> federatedToolCallbacks() {
        if (toolCallbackProvider == null) {
            return List.of();
        }
        ToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        return List.of(provider.getToolCallbacks());
    }

    private static UUID taskId(RunnableConfig config) {
        return config.threadId()
                .map(UUID::fromString)
                .orElseThrow(() -> new IllegalStateException("threadId (taskId) is required to run the SOLO graph"));
    }
}
