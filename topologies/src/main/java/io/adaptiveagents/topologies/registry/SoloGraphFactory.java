package io.adaptiveagents.topologies.registry;

import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.topologies.solo.SoloState;
import io.adaptiveagents.workload.tools.ResearchTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * SOLO topology: a single-node graph that runs a bounded ReAct loop (max {@value #MAX_ITERATIONS}
 * iterations) with the workload {@link ResearchTools} registered as Spring AI tools.
 *
 * <p>The node never rethrows past the graph: tool errors and loop exhaustion are folded into
 * {@link SoloState#ERROR} plus {@code degraded=true}, so a bad run degrades the state instead of
 * crashing the topology.</p>
 */
public final class SoloGraphFactory implements GraphFactory {

    private static final Logger log = LoggerFactory.getLogger(SoloGraphFactory.class);

    static final String AGENT_NODE = "solo-agent";
    public static final int MAX_ITERATIONS = 3;

    private final ChatClient.Builder chatClientBuilder;
    private final ResearchTools researchTools;
    private final NodeGuard nodeGuard;

    /**
     * @param chatClientBuilder Spring AI chat client builder; may be {@code null} in wiring-only
     *     contexts (the graph is unusable until {@link #build()} is called with a real builder)
     * @param researchTools     workload tool surface registered with the model
     * @param nodeGuard         per-iteration budget/deadline seam
     */
    public SoloGraphFactory(ChatClient.Builder chatClientBuilder, ResearchTools researchTools, NodeGuard nodeGuard) {
        this.chatClientBuilder = chatClientBuilder;
        this.researchTools = researchTools;
        this.nodeGuard = nodeGuard;
    }

    @Override
    public CompiledGraph<SoloState> build() {
        try {
            ChatClient chatClient = buildChatClient();
            StateGraph<SoloState> graph = new StateGraph<>(SoloState::new);
            graph.addNode(AGENT_NODE, node_async(agentAction(chatClient)));
            graph.addEdge(StateGraph.START, AGENT_NODE);
            graph.addEdge(AGENT_NODE, StateGraph.END);
            return graph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to assemble SOLO graph", e);
        }
    }

    private NodeActionWithConfig<SoloState> agentAction(ChatClient chatClient) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            List<String> transcript = new ArrayList<>();
            String error = null;

            for (int i = 0; i < MAX_ITERATIONS; i++) {
                try {
                    nodeGuard.check(taskId);
                } catch (Exception e) {
                    log.warn("Guard rejected task {} at iteration {}: {}", taskId, i, e.getMessage());
                    return Map.of(
                            SoloState.DEGRADED, true,
                            SoloState.ERROR, "guard rejected: " + e.getMessage(),
                            SoloState.TOOL_TRANSCRIPT, transcript);
                }

                try {
                    ChatResponse response = chatClient.prompt()
                            .user(state.prompt())
                            .tools(researchTools)
                            .call()
                            .chatResponse();
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

    private ChatClient buildChatClient() {
        if (chatClientBuilder == null) {
            throw new IllegalStateException("ChatClient.Builder is not available; cannot build SOLO graph");
        }
        return chatClientBuilder.build();
    }

    private static UUID taskId(RunnableConfig config) {
        return config.threadId()
                .map(UUID::fromString)
                .orElseThrow(() -> new IllegalStateException("threadId (taskId) is required to run the SOLO graph"));
    }
}
