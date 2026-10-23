package io.adaptiveagents.topologies.registry;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.skills.SkillTools;
import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.guard.GuardException;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.topologies.supervisor.SupervisorState;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SUPERVISOR_TEAM topology: a pure-routing supervisor that drives three subagent nodes
 * (researcher &rarr; writer &harr; critic) with a structurally bounded critic loop.
 *
 * <p>The supervisor is a <em>routing-only</em> node (no model call). It decides the next node from
 * state alone via a langgraph4j conditional edge: {@code research==null → researcher},
 * {@code draft==null → writer}, {@code !evaluated → critic}, {@code accepted → finalize}, or
 * {@code criticRounds >= MAX_CRITIC_ROUNDS → finalize}. Every subagent returns to the supervisor,
 * so the graph is a loop; the only exit is the {@code finalize} node (FINISH path). The critic loop
 * is hard-capped at {@value #MAX_CRITIC_ROUNDS} revisions — an unbounded loop is forbidden.</p>
 *
 * <p>Each subagent makes exactly one {@link ChatClient} call per invocation (researcher distills
 * search findings, writer drafts the report, critic renders a scripted ACCEPT/REVISE verdict) and
 * folds any failure into {@link SupervisorState#ERROR} + {@code degraded=true}, after which the
 * supervisor routes to {@code finalize} with a partial result. A {@link GuardException} at a
 * researcher/writer/critic/finalize boundary is folded with {@link SupervisorState#GUARD_REJECTED}
 * so the runner can map it to a terminal status; every guard fold preserves the team artifact so
 * far (research/draft/critique) as the {@link SupervisorState#PARTIAL_RESULT} snapshot — the
 * supervisor still routes to {@code finalize}, so a budget cut finishes with the partial artifact
 * instead of dropping it. The critic verdict is parsed deterministically: anything not starting
 * with {@code ACCEPT} is treated as {@code REVISE}.</p>
 */
public final class SupervisorGraphFactory implements GraphFactory {

    private static final Logger log = LoggerFactory.getLogger(SupervisorGraphFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Structural cap on critic revisions. Never unbounded. */
    public static final int MAX_CRITIC_ROUNDS = 2;

    static final String RESEARCHER_NODE = "researcher";
    static final String WRITER_NODE = "writer";
    static final String CRITIC_NODE = "critic";
    static final String SUPERVISOR_NODE = "supervisor";
    static final String FINALIZE_NODE = "finalize";

    static final String ROUTE_RESEARCHER = "researcher";
    static final String ROUTE_WRITER = "writer";
    static final String ROUTE_CRITIC = "critic";
    static final String ROUTE_FINALIZE = "finalize";

    private static final Map<String, String> SUPERVISOR_ROUTES = Map.of(
            ROUTE_RESEARCHER, RESEARCHER_NODE,
            ROUTE_WRITER, WRITER_NODE,
            ROUTE_CRITIC, CRITIC_NODE,
            ROUTE_FINALIZE, FINALIZE_NODE);

    private static final String ACCEPT_PREFIX = "ACCEPT";
    private static final String REVISE_PREFIX = "REVISE";

    private final ChatClient.Builder chatClientBuilder;
    private final SearchTool searchTool;
    private final NodeGuard nodeGuard;
    private final CostTracker costTracker;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final SkillTools skillTools;
    private static final ThreadLocal<Boolean> IN_TOOL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public SupervisorGraphFactory(
            ChatClient.Builder chatClientBuilder,
            SearchTool searchTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            SkillTools skillTools) {
        this.chatClientBuilder = chatClientBuilder;
        this.searchTool = searchTool;
        this.nodeGuard = nodeGuard;
        this.costTracker = costTracker;
        this.tracerProvider = tracerProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.skillTools = skillTools;
    }

    public SupervisorGraphFactory(
            ChatClient.Builder chatClientBuilder,
            SearchTool searchTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        this(chatClientBuilder, searchTool, nodeGuard, costTracker, tracerProvider, toolCallbackProvider, null);
    }

    public SupervisorGraphFactory(
            ChatClient.Builder chatClientBuilder,
            SearchTool searchTool,
            NodeGuard nodeGuard,
            CostTracker costTracker,
            ObjectProvider<Tracer> tracerProvider) {
        this(chatClientBuilder, searchTool, nodeGuard, costTracker, tracerProvider, null);
    }

    public SupervisorGraphFactory(ChatClient.Builder chatClientBuilder, SearchTool searchTool, NodeGuard nodeGuard, CostTracker costTracker) {
        this(chatClientBuilder, searchTool, nodeGuard, costTracker, null);
    }

    @Override
    public StateGraph<SupervisorState> graph() {
        try {
            ChatClient chatClient = buildChatClient();
            List<ToolCallback> federatedTools = federatedToolCallbacks();
            StateGraph<SupervisorState> graph = new StateGraph<>(SupervisorState::new);
            graph.addNode(RESEARCHER_NODE, node_async(researcherNode(chatClient, federatedTools)));
            graph.addNode(WRITER_NODE, node_async(writerNode(chatClient, federatedTools)));
            graph.addNode(CRITIC_NODE, node_async(criticNode(chatClient, federatedTools)));
            graph.addNode(SUPERVISOR_NODE, node_async(supervisorNode()));
            graph.addNode(FINALIZE_NODE, node_async(finalizeNode()));

            graph.addEdge(StateGraph.START, SUPERVISOR_NODE);
            graph.addConditionalEdges(SUPERVISOR_NODE, edge_async(supervisorEdge()), SUPERVISOR_ROUTES);
            graph.addEdge(RESEARCHER_NODE, SUPERVISOR_NODE);
            graph.addEdge(WRITER_NODE, SUPERVISOR_NODE);
            graph.addEdge(CRITIC_NODE, SUPERVISOR_NODE);
            graph.addEdge(FINALIZE_NODE, StateGraph.END);

            return graph;
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to assemble SUPERVISOR_TEAM graph", e);
        }
    }

    /**
     * Pure routing node: performs no work, no model call, and is intentionally unguarded — the
     * routing decision lives on the conditional edge attached to this node, so there is no node
     * boundary to enforce.
     */
    private NodeActionWithConfig<SupervisorState> supervisorNode() {
        return (state, config) -> Map.of();
    }

    /**
     * Conditional routing: maps state to the next node label. Degradation short-circuits straight to
     * {@code finalize}; otherwise the decision follows the researcher &rarr; writer &harr; critic
     * lifecycle with the critic cap forcing {@code finalize} when revisions are exhausted.
     */
    private EdgeAction<SupervisorState> supervisorEdge() {
        return state -> {
            if (state.degraded()) {
                return ROUTE_FINALIZE;
            }
            if (state.research() == null) {
                return ROUTE_RESEARCHER;
            }
            if (state.draft() == null) {
                return ROUTE_WRITER;
            }
            if (!state.evaluated()) {
                return ROUTE_CRITIC;
            }
            if (state.accepted() || state.criticRounds() >= MAX_CRITIC_ROUNDS) {
                return ROUTE_FINALIZE;
            }
            return ROUTE_WRITER;
        };
    }

    private NodeActionWithConfig<SupervisorState> researcherNode(ChatClient chatClient, List<ToolCallback> federatedTools) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(RESEARCHER_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(RESEARCHER_NODE, e, state);
            }
            try {
                List<SearchResult> results = traceToolCall("web_search", taskId, () -> searchTool.search(state.prompt()));
                String research = results.isEmpty() ? "No sources found." : distill(chatClient, federatedTools, state.prompt(), results, taskId);
                if (research == null || research.isBlank()) {
                    return fold("researcher: empty research response");
                }
                return Map.of(SupervisorState.RESEARCH, research);
            } catch (Exception e) {
                log.warn("researcher failed for task {}: {}", taskId, e.getMessage());
                return fold("researcher: " + e.getMessage());
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

    private NodeActionWithConfig<SupervisorState> writerNode(ChatClient chatClient, List<ToolCallback> federatedTools) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(WRITER_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(WRITER_NODE, e, state);
            }
            try {
                String draft = writeDraft(chatClient, federatedTools, state, taskId);
                if (draft == null || draft.isBlank()) {
                    return fold("writer: empty draft response");
                }
                return Map.of(SupervisorState.DRAFT, draft, SupervisorState.EVALUATED, false);
            } catch (Exception e) {
                log.warn("writer failed for task {}: {}", taskId, e.getMessage());
                return fold("writer: " + e.getMessage());
            }
        };
    }

    private NodeActionWithConfig<SupervisorState> criticNode(ChatClient chatClient, List<ToolCallback> federatedTools) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(CRITIC_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(CRITIC_NODE, e, state);
            }
            try {
                String verdict = critique(chatClient, federatedTools, state.draft(), taskId);
                if (verdict == null || verdict.isBlank()) {
                    return fold("critic: empty verdict response");
                }
                boolean accepted = parseAccept(verdict.trim());
                Map<String, Object> result = new HashMap<>();
                result.put(SupervisorState.CRITIQUE, verdict);
                result.put(SupervisorState.CRITIC_ROUNDS, state.criticRounds() + 1);
                result.put(SupervisorState.ACCEPTED, accepted);
                result.put(SupervisorState.EVALUATED, true);
                return result;
            } catch (Exception e) {
                log.warn("critic failed for task {}: {}", taskId, e.getMessage());
                return fold("critic: " + e.getMessage());
            }
        };
    }

    private NodeActionWithConfig<SupervisorState> finalizeNode() {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(FINALIZE_NODE, taskId);
            } catch (GuardException e) {
                return foldGuard(FINALIZE_NODE, e, state);
            }
            boolean forcedFinish = !state.accepted() && state.criticRounds() >= MAX_CRITIC_ROUNDS;
            boolean degraded = state.degraded() || forcedFinish;
            String resultJson = buildResultJson(state, degraded);
            return Map.of(
                    SupervisorState.RESULT, resultJson,
                    SupervisorState.DEGRADED, degraded,
                    SupervisorState.FINISHED, true);
        };
    }

    /** Deterministic verdict parse: only a leading {@code ACCEPT} accepts; anything else revises. */
    private boolean parseAccept(String verdict) {
        if (verdict.startsWith(ACCEPT_PREFIX)) {
            return true;
        }
        if (!verdict.startsWith(REVISE_PREFIX)) {
            log.warn("critic produced unrecognized verdict (treating as REVISE): {}", verdict);
        }
        return false;
    }

    private String distill(ChatClient chatClient, List<ToolCallback> federatedTools, String prompt, List<SearchResult> results, UUID taskId) {
        StringBuilder sb = new StringBuilder("Distill the following search results into concise research findings for: ")
                .append(prompt).append("\n");
        for (SearchResult result : results) {
            sb.append("- ").append(result.title()).append(": ").append(result.snippet()).append("\n");
        }
        return callText(chatClient, federatedTools, sb.toString(), taskId, RESEARCHER_NODE);
    }

    private String writeDraft(ChatClient chatClient, List<ToolCallback> federatedTools, SupervisorState state, UUID taskId) {
        StringBuilder sb = new StringBuilder("Write a report draft (revision ")
                .append(state.criticRounds() + 1)
                .append(") based on the following research:\n")
                .append(state.research())
                .append("\n");
        if (state.critique() != null) {
            sb.append("Address the previous critique:\n").append(state.critique()).append("\n");
        }
        return callText(chatClient, federatedTools, sb.toString(), taskId, WRITER_NODE);
    }

    private String critique(ChatClient chatClient, List<ToolCallback> federatedTools, String draft, UUID taskId) {
        String prompt = "Critique the following report draft and respond with a single line starting with "
                + ACCEPT_PREFIX + " if it is ready, or " + REVISE_PREFIX + " followed by specific feedback:\n\n"
                + draft;
        return callText(chatClient, federatedTools, prompt, taskId, CRITIC_NODE);
    }

    private static String buildResultJson(SupervisorState state, boolean degraded) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("research", state.research() == null ? "" : state.research());
        root.put("draft", state.draft() == null ? "" : state.draft());
        root.put("finalCritique", state.critique() == null ? "" : state.critique());
        root.put("criticRounds", state.criticRounds());
        root.put("degraded", degraded);
        return root.toString();
    }

    private static Map<String, Object> fold(String message) {
        return Map.of(
                SupervisorState.DEGRADED, true,
                SupervisorState.ERROR, message);
    }

    private static Map<String, Object> foldGuard(String stage, GuardException e, SupervisorState state) {
        return Map.of(
                SupervisorState.DEGRADED, true,
                SupervisorState.ERROR, stage + ": guard rejected: " + e.getMessage(),
                SupervisorState.GUARD_REJECTED, e.getClass().getSimpleName(),
                SupervisorState.PARTIAL_RESULT, buildResultJson(state, true));
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
            throw new IllegalStateException("ChatClient.Builder is not available; cannot build SUPERVISOR_TEAM graph");
        }
        return chatClientBuilder.build();
    }

    private static UUID taskId(RunnableConfig config) {
        return config.threadId()
                .map(UUID::fromString)
                .orElseThrow(() -> new IllegalStateException("threadId (taskId) is required to run the SUPERVISOR_TEAM graph"));
    }
}
