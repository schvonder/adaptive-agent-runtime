package io.adaptiveagents.topologies.registry;

import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.skills.SkillTools;
import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.chain.ChainState;
import io.adaptiveagents.topologies.guard.GuardException;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.workload.tools.Citation;
import io.adaptiveagents.workload.tools.CiteTool;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
 * CHAIN topology: four sequential nodes plan &rarr; search &rarr; summarize &rarr; cite.
 *
 * <p>The plan and summarize nodes make a single tool-free {@link ChatClient} call each; search and
 * cite are direct tool invocations (no model). A node that fails folds the error into
 * {@link ChainState#ERROR}/{@link ChainState#STAGE}/{@link ChainState#DEGRADED} and every downstream
 * node no-ops, so the graph never rethrows past a node. A {@link GuardException} at a node boundary
 * is folded separately with the {@link ChainState#GUARD_REJECTED} flag so the runner can map it to a
 * terminal status; the completed stage outputs are preserved as the
 * {@link ChainState#PARTIAL_RESULT} snapshot with the stage the chain stopped at (CHAIN degradation:
 * stop-at-stage). An empty search result set is a graceful (non-degraded) path that skips the
 * summarize model call.</p>
 */
public final class ChainGraphFactory implements GraphFactory {

    private static final Logger log = LoggerFactory.getLogger(ChainGraphFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String PLAN_NODE = "plan";
    static final String SEARCH_NODE = "search";
    static final String SUMMARIZE_NODE = "summarize";
    static final String CITE_NODE = "cite";

    static final int MAX_QUERIES = 3;
    static final String EMPTY_SUMMARY = "No sources found.";

    private final ChatClient.Builder chatClientBuilder;
    private final SearchTool searchTool;
    private final CiteTool citeTool;
    private final NodeGuard nodeGuard;
    private final CostTracker costTracker;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final SkillTools skillTools;
    private static final ThreadLocal<Boolean> IN_TOOL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public ChainGraphFactory(
            ChatClient.Builder chatClientBuilder,
            SearchTool searchTool,
            CiteTool citeTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            SkillTools skillTools) {
        this.chatClientBuilder = chatClientBuilder;
        this.searchTool = searchTool;
        this.citeTool = citeTool;
        this.nodeGuard = nodeGuard;
        this.costTracker = costTracker;
        this.tracerProvider = tracerProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.skillTools = skillTools;
    }

    public ChainGraphFactory(
            ChatClient.Builder chatClientBuilder,
            SearchTool searchTool,
            CiteTool citeTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        this(chatClientBuilder, searchTool, citeTool, nodeGuard, costTracker, tracerProvider, toolCallbackProvider, null);
    }

    public ChainGraphFactory(
            ChatClient.Builder chatClientBuilder,
            SearchTool searchTool,
            CiteTool citeTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider) {
        this(chatClientBuilder, searchTool, citeTool, nodeGuard, costTracker, tracerProvider, null);
    }

    public ChainGraphFactory(ChatClient.Builder chatClientBuilder, SearchTool searchTool, CiteTool citeTool, NodeGuard nodeGuard, CostTracker costTracker) {
        this(chatClientBuilder, searchTool, citeTool, nodeGuard, costTracker, null);
    }

    @Override
    public StateGraph<ChainState> graph() {
        try {
            ChatClient chatClient = buildChatClient();
            List<ToolCallback> federatedTools = federatedToolCallbacks();
            StateGraph<ChainState> graph = new StateGraph<>(ChainState::new);
            graph.addNode(PLAN_NODE, node_async(planNode(chatClient, federatedTools)));
            graph.addNode(SEARCH_NODE, node_async(searchNode()));
            graph.addNode(SUMMARIZE_NODE, node_async(summarizeNode(chatClient, federatedTools)));
            graph.addNode(CITE_NODE, node_async(citeNode()));
            graph.addEdge(StateGraph.START, PLAN_NODE);
            graph.addEdge(PLAN_NODE, SEARCH_NODE);
            graph.addEdge(SEARCH_NODE, SUMMARIZE_NODE);
            graph.addEdge(SUMMARIZE_NODE, CITE_NODE);
            graph.addEdge(CITE_NODE, StateGraph.END);
            return graph;
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to assemble CHAIN graph", e);
        }
    }

    private NodeActionWithConfig<ChainState> planNode(ChatClient chatClient, List<ToolCallback> federatedTools) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(PLAN_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(PLAN_NODE, e, state);
            }
            try {
                String text = callText(chatClient, federatedTools, "Create a concise search plan for: " + state.prompt(), taskId, PLAN_NODE);
                if (text == null || text.isBlank()) {
                    return fold(PLAN_NODE, "empty plan response");
                }
                return Map.of(ChainState.PLAN, text);
            } catch (Exception e) {
                log.warn("plan failed for task {}: {}", taskId, e.getMessage());
                return fold(PLAN_NODE, e.getMessage());
            }
        };
    }

    private NodeActionWithConfig<ChainState> searchNode() {
        return (state, config) -> {
            if (state.degraded()) {
                return Map.of();
            }
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(SEARCH_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(SEARCH_NODE, e, state);
            }
            try {
                List<SearchResult> merged = new ArrayList<>();
                for (String query : deriveQueries(state.plan())) {
                    merged.addAll(traceToolCall("web_search", taskId, () -> searchTool.search(query)));
                }
                return Map.of(ChainState.SEARCH_RESULTS, merged);
            } catch (Exception e) {
                log.warn("search failed for task {}: {}", taskId, e.getMessage());
                return fold(SEARCH_NODE, e.getMessage());
            }
        };
    }

    private NodeActionWithConfig<ChainState> summarizeNode(ChatClient chatClient, List<ToolCallback> federatedTools) {
        return (state, config) -> {
            if (state.degraded()) {
                return Map.of();
            }
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(SUMMARIZE_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(SUMMARIZE_NODE, e, state);
            }
            List<SearchResult> results = state.searchResults();
            if (results.isEmpty()) {
                return Map.of(ChainState.SUMMARY, EMPTY_SUMMARY);
            }
            try {
                String text = callText(chatClient, federatedTools, summarizePrompt(results), taskId, SUMMARIZE_NODE);
                if (text == null || text.isBlank()) {
                    return fold(SUMMARIZE_NODE, "empty summary response");
                }
                return Map.of(ChainState.SUMMARY, text);
            } catch (Exception e) {
                log.warn("summarize failed for task {}: {}", taskId, e.getMessage());
                return fold(SUMMARIZE_NODE, e.getMessage());
            }
        };
    }

    private NodeActionWithConfig<ChainState> citeNode() {
        return (state, config) -> {
            if (state.degraded()) {
                return Map.of();
            }
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(CITE_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(CITE_NODE, e, state);
            }
            try {
                List<Citation> citations = traceToolCall("cite_sources", taskId, () -> citeTool.cite(state.searchResults(), null));
                String resultJson = buildResultJson(state.plan(), state.summary(), citations, state.degraded());
                return Map.of(ChainState.CITATIONS, citations, ChainState.RESULT, resultJson);
            } catch (Exception e) {
                log.warn("cite failed for task {}: {}", taskId, e.getMessage());
                return fold(CITE_NODE, e.getMessage());
            }
        };
    }

    private <T> T traceToolCall(String toolName, UUID taskId, java.util.function.Supplier<T> action) {
        Tracer tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        if (tracer == null || IN_TOOL_CALL.get()) {
            return action.get();
        }
        IN_TOOL_CALL.set(true);
        Span span = tracer.nextSpan().name(toolName);
        if (taskId != null) {
            span.tag("task_id", taskId.toString());
        }
        span.start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return action.get();
        } catch (Throwable t) {
            span.error(t);
            throw t;
        } finally {
            try {
                span.end();
            } finally {
                IN_TOOL_CALL.set(false);
            }
        }
    }

    private static List<String> deriveQueries(String plan) {
        if (plan == null || plan.isBlank()) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        for (String line : plan.split("\\R")) {
            String query = line.trim();
            if (!query.isEmpty()) {
                queries.add(query);
                if (queries.size() >= MAX_QUERIES) {
                    break;
                }
            }
        }
        return queries;
    }

    private static String summarizePrompt(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder("Summarize the following search results:\n");
        for (SearchResult result : results) {
            sb.append("- ").append(result.title()).append(": ").append(result.snippet()).append("\n");
        }
        return sb.toString();
    }

    private static String buildResultJson(String plan, String summary, List<Citation> citations, boolean degraded) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("plan", plan == null ? "" : plan);
        root.put("summary", summary == null ? "" : summary);
        ArrayNode citationArray = root.putArray("citations");
        for (Citation citation : citations) {
            ObjectNode node = citationArray.addObject();
            node.put("index", citation.index());
            node.put("title", citation.title());
            node.put("url", citation.url());
        }
        root.put("degraded", degraded);
        return root.toString();
    }

    private static Map<String, Object> fold(String stage, String message) {
        return Map.of(
                ChainState.DEGRADED, true,
                ChainState.ERROR, stage + " failed: " + message,
                ChainState.STAGE, stage);
    }

    private static Map<String, Object> foldGuard(String stage, GuardException e, ChainState state) {
        return Map.of(
                ChainState.DEGRADED, true,
                ChainState.ERROR, stage + " failed: " + e.getMessage(),
                ChainState.STAGE, stage,
                ChainState.GUARD_REJECTED, e.getClass().getSimpleName(),
                ChainState.PARTIAL_RESULT, partialJson(stage, state));
    }

    private static String partialJson(String stoppedAt, ChainState state) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("stoppedAt", stoppedAt);
        if (state.plan() != null) {
            root.put("plan", state.plan());
        }
        if (state.summary() != null) {
            root.put("summary", state.summary());
        }
        root.put("searchResults", state.searchResults().size());
        root.put("degraded", true);
        return root.toString();
    }

    private String callText(ChatClient chatClient, List<ToolCallback> federatedTools, String userPrompt, UUID taskId, String nodeName) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(userPrompt);
        if (skillTools != null) {
            spec = spec.tools(skillTools);
        }
        if (!federatedTools.isEmpty()) {
            spec = spec.toolCallbacks(federatedTools);
        }
        ChatResponse response = spec.call().chatResponse();
        costTracker.record(nodeName, taskId, response);
        return response.getResult() == null ? null : response.getResult().getOutput().getText();
    }

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

    private ChatClient buildChatClient() {
        if (chatClientBuilder == null) {
            throw new IllegalStateException("ChatClient.Builder is not available; cannot build CHAIN graph");
        }
        return chatClientBuilder.build();
    }

    private static UUID taskId(RunnableConfig config) {
        return config.threadId()
                .map(UUID::fromString)
                .orElseThrow(() -> new IllegalStateException("threadId (taskId) is required to run the CHAIN graph"));
    }
}
