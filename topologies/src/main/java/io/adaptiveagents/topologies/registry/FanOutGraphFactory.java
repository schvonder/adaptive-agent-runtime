package io.adaptiveagents.topologies.registry;

import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.fanout.FanOutState;
import io.adaptiveagents.topologies.guard.GuardException;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * FAN_OUT/GATHER topology: {@value #FAN_WIDTH} fixed parallel branch nodes merged by a gather node.
 *
 * <p>Fan-out is expressed as {@value #FAN_WIDTH} edges from {@link StateGraph#START} into the branch
 * nodes; langgraph4j detects the multi-target source and wraps the branches in a
 * {@code ParallelNode} that runs them concurrently and merges their partial updates through the
 * graph's channels. The {@link FanOutState#SECTIONS} and {@link FanOutState#FAILURES} keys are
 * declared as appender channels so the branch outputs accumulate instead of overwriting.</p>
 *
 * <p>Each branch runs on an unpooled virtual-thread executor (the fan width is structurally fixed at
 * {@value #FAN_WIDTH}, never pooled): it guards, searches with a per-branch query variant, makes one
 * {@link ChatClient} call to summarize, and returns a single section. A branch that fails catches
 * in-branch and returns a failure marker on the {@link FanOutState#FAILURES} appender, so the gather
 * node still runs with the survivor sections and {@code degraded=true}. A {@link GuardException} at
 * a branch or gather boundary is folded with the {@link FanOutState#GUARD_REJECTED} flag so the
 * runner can map it to a terminal status (FAN_OUT degradation: reduce width — a branch whose
 * boundary is rejected is skipped with a failure marker; the gather fold preserves the gathered
 * sections as the {@link FanOutState#PARTIAL_RESULT} snapshot). Only when every branch fails is
 * {@link FanOutState#ERROR} set.</p>
 */
public final class FanOutGraphFactory implements GraphFactory {

    private static final Logger log = LoggerFactory.getLogger(FanOutGraphFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fixed fan width. Not runtime-configurable by design; structural bound on concurrency. */
    public static final int FAN_WIDTH = 3;

    /**
     * Unpooled virtual-thread executor for branch execution. Deliberately NOT pooled — virtual
     * threads must never be pooled; the graph's fixed fan width of {@value #FAN_WIDTH} is the only
     * concurrency bound.
     */
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    static final String GATHER_NODE = "gather";

    private final ChatClient.Builder chatClientBuilder;
    private final SearchTool searchTool;
    private final NodeGuard nodeGuard;
    private final CostTracker costTracker;

    /**
     * @param chatClientBuilder Spring AI chat client builder; may be {@code null} in wiring-only
     *     contexts (the graph is unusable until {@link #graph()} is called with a real builder)
     * @param searchTool        workload search tool invoked directly by each branch
     * @param nodeGuard         per-node budget/deadline seam
     * @param costTracker       per-response token/cost accounting
     */
    public FanOutGraphFactory(ChatClient.Builder chatClientBuilder, SearchTool searchTool, NodeGuard nodeGuard, CostTracker costTracker) {
        this.chatClientBuilder = chatClientBuilder;
        this.searchTool = searchTool;
        this.nodeGuard = nodeGuard;
        this.costTracker = costTracker;
    }

    @Override
    public StateGraph<FanOutState> graph() {
        try {
            ChatClient chatClient = buildChatClient();
            Map<String, Channel<?>> channels = Map.of(
                    FanOutState.SECTIONS, Channels.<String>appenderWithDuplicate(ArrayList::new),
                    FanOutState.FAILURES, Channels.<String>appenderWithDuplicate(ArrayList::new));

            StateGraph<FanOutState> graph = new StateGraph<>(channels, FanOutState::new);

            for (int branch = 1; branch <= FAN_WIDTH; branch++) {
                graph.addNode(branchNodeId(branch), branchNode(branch, chatClient));
            }
            graph.addNode(GATHER_NODE, node_async(gatherNode()));

            for (int branch = 1; branch <= FAN_WIDTH; branch++) {
                graph.addEdge(StateGraph.START, branchNodeId(branch));
                graph.addEdge(branchNodeId(branch), GATHER_NODE);
            }
            graph.addEdge(GATHER_NODE, StateGraph.END);

            return graph;
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to assemble FAN_OUT graph", e);
        }
    }

    /**
     * Builds a branch node action that runs on the unpooled virtual-thread executor. The work is
     * scheduled via {@link CompletableFuture#supplyAsync(java.util.function.Supplier, Executor)}
     * so the parallel node's {@code allOfFailFast} sees genuinely concurrent branches.
     */
    private AsyncNodeActionWithConfig<FanOutState> branchNode(int branch, ChatClient chatClient) {
        return (state, config) ->
                CompletableFuture.supplyAsync(() -> runBranch(branch, state, config, chatClient), VIRTUAL);
    }

    private Map<String, Object> runBranch(int branch, FanOutState state, RunnableConfig config, ChatClient chatClient) {
        UUID taskId = taskId(config);
        try {
            nodeGuard.check(branchNodeId(branch), taskId);
        } catch (GuardException e) {
            return failureGuard(branch, e);
        }
        try {
            List<SearchResult> results = searchTool.search(state.prompt() + " aspect " + branch);
            String section = results.isEmpty()
                    ? "No sources found for aspect " + branch
                    : summarize(chatClient, branch, results, taskId);
            if (section == null || section.isBlank()) {
                return failure(branch, "empty summary response");
            }
            return Map.of(FanOutState.SECTIONS, section);
        } catch (Exception e) {
            log.warn("branch {} failed for task {}: {}", branch, taskId, e.getMessage());
            return failure(branch, e.getMessage());
        }
    }

    private NodeActionWithConfig<FanOutState> gatherNode() {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(GATHER_NODE, taskId);
            } catch (GuardException e) {
                List<String> sections = state.sections();
                List<String> failures = state.failures();
                return Map.of(
                        FanOutState.DEGRADED, true,
                        FanOutState.ERROR, "gather failed: " + e.getMessage(),
                        FanOutState.GUARD_REJECTED, e.getClass().getSimpleName(),
                        FanOutState.PARTIAL_RESULT, buildResultJson(sections, failures, true));
            }
            List<String> sections = state.sections();
            List<String> failures = state.failures();
            boolean degraded = !failures.isEmpty();
            String resultJson = buildResultJson(sections, failures, degraded);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put(FanOutState.RESULT, resultJson);
            result.put(FanOutState.DEGRADED, degraded);
            if (sections.isEmpty()) {
                result.put(FanOutState.ERROR, "all " + FAN_WIDTH + " branches failed");
            }
            return result;
        };
    }

    private static Map<String, Object> failure(int branch, String message) {
        return Map.of(FanOutState.FAILURES, "branch-" + branch + ": " + (message == null ? "unknown error" : message));
    }

    private static Map<String, Object> failureGuard(int branch, GuardException e) {
        return Map.of(
                FanOutState.FAILURES, "branch-" + branch + ": guard rejected: " + e.getMessage(),
                FanOutState.GUARD_REJECTED, e.getClass().getSimpleName());
    }

    private String summarize(ChatClient chatClient, int branch, List<SearchResult> results, UUID taskId) {
        StringBuilder sb = new StringBuilder("Summarize these results for aspect ").append(branch).append(":\n");
        for (SearchResult result : results) {
            sb.append("- ").append(result.title()).append(": ").append(result.snippet()).append("\n");
        }
        ChatResponse response = chatClient.prompt().user(sb.toString()).call().chatResponse();
        costTracker.record(branchNodeId(branch), taskId, response);
        return response.getResult() == null ? null : response.getResult().getOutput().getText();
    }

    private static String buildResultJson(List<String> sections, List<String> failures, boolean degraded) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode sectionsArray = root.putArray("sections");
        for (String section : sections) {
            sectionsArray.add(section);
        }
        ObjectNode branchStatus = root.putObject("branchStatus");
        for (int branch = 1; branch <= FAN_WIDTH; branch++) {
            String id = branchNodeId(branch);
            branchStatus.put(id, isFailed(failures, id) ? "failed" : "ok");
        }
        root.put("degraded", degraded);
        return root.toString();
    }

    private static boolean isFailed(List<String> failures, String branchId) {
        String prefix = branchId + ":";
        return failures.stream().anyMatch(f -> f != null && f.startsWith(prefix));
    }

    private static String branchNodeId(int branch) {
        return "branch-" + branch;
    }

    private ChatClient buildChatClient() {
        if (chatClientBuilder == null) {
            throw new IllegalStateException("ChatClient.Builder is not available; cannot build FAN_OUT graph");
        }
        return chatClientBuilder.build();
    }

    private static UUID taskId(RunnableConfig config) {
        return config.threadId()
                .map(UUID::fromString)
                .orElseThrow(() -> new IllegalStateException("threadId (taskId) is required to run the FAN_OUT graph"));
    }
}
