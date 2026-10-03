package io.adaptiveagents.topologies.registry;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.topologies.guard.NodeGuard;
import io.adaptiveagents.topologies.supervisor.SupervisorState;
import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

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
 * supervisor routes to {@code finalize} with a partial result. The critic verdict is parsed
 * deterministically: anything not starting with {@code ACCEPT} is treated as {@code REVISE}.</p>
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

    /**
     * @param chatClientBuilder Spring AI chat client builder; may be {@code null} in wiring-only
     *     contexts (the graph is unusable until {@link #build()} is called with a real builder)
     * @param searchTool        workload search tool invoked directly by the researcher node
     * @param nodeGuard         per-node budget/deadline seam
     */
    public SupervisorGraphFactory(ChatClient.Builder chatClientBuilder, SearchTool searchTool, NodeGuard nodeGuard) {
        this.chatClientBuilder = chatClientBuilder;
        this.searchTool = searchTool;
        this.nodeGuard = nodeGuard;
    }

    @Override
    public CompiledGraph<SupervisorState> build() {
        try {
            ChatClient chatClient = buildChatClient();
            StateGraph<SupervisorState> graph = new StateGraph<>(SupervisorState::new);
            graph.addNode(RESEARCHER_NODE, node_async(researcherNode(chatClient)));
            graph.addNode(WRITER_NODE, node_async(writerNode(chatClient)));
            graph.addNode(CRITIC_NODE, node_async(criticNode(chatClient)));
            graph.addNode(SUPERVISOR_NODE, node_async(supervisorNode()));
            graph.addNode(FINALIZE_NODE, node_async(finalizeNode()));

            graph.addEdge(StateGraph.START, SUPERVISOR_NODE);
            graph.addConditionalEdges(SUPERVISOR_NODE, edge_async(supervisorEdge()), SUPERVISOR_ROUTES);
            graph.addEdge(RESEARCHER_NODE, SUPERVISOR_NODE);
            graph.addEdge(WRITER_NODE, SUPERVISOR_NODE);
            graph.addEdge(CRITIC_NODE, SUPERVISOR_NODE);
            graph.addEdge(FINALIZE_NODE, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to assemble SUPERVISOR_TEAM graph", e);
        }
    }

    /**
     * Pure routing node: performs no work, no model call. The routing decision itself lives on the
     * conditional edge attached to this node, so the supervisor contributes only the loop topology.
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

    private NodeActionWithConfig<SupervisorState> researcherNode(ChatClient chatClient) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(taskId);
            } catch (Exception e) {
                return fold("researcher: guard rejected: " + e.getMessage());
            }
            try {
                List<SearchResult> results = searchTool.search(state.prompt());
                String research = results.isEmpty() ? "No sources found." : distill(chatClient, state.prompt(), results);
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

    private NodeActionWithConfig<SupervisorState> writerNode(ChatClient chatClient) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(taskId);
            } catch (Exception e) {
                return fold("writer: guard rejected: " + e.getMessage());
            }
            try {
                String draft = writeDraft(chatClient, state);
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

    private NodeActionWithConfig<SupervisorState> criticNode(ChatClient chatClient) {
        return (state, config) -> {
            UUID taskId = taskId(config);
            try {
                nodeGuard.check(taskId);
            } catch (Exception e) {
                return fold("critic: guard rejected: " + e.getMessage());
            }
            try {
                String verdict = critique(chatClient, state.draft());
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

    private static String distill(ChatClient chatClient, String prompt, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder("Distill the following search results into concise research findings for: ")
                .append(prompt).append("\n");
        for (SearchResult result : results) {
            sb.append("- ").append(result.title()).append(": ").append(result.snippet()).append("\n");
        }
        return callText(chatClient, sb.toString());
    }

    private static String writeDraft(ChatClient chatClient, SupervisorState state) {
        StringBuilder sb = new StringBuilder("Write a report draft (revision ")
                .append(state.criticRounds() + 1)
                .append(") based on the following research:\n")
                .append(state.research())
                .append("\n");
        if (state.critique() != null) {
            sb.append("Address the previous critique:\n").append(state.critique()).append("\n");
        }
        return callText(chatClient, sb.toString());
    }

    private static String critique(ChatClient chatClient, String draft) {
        String prompt = "Critique the following report draft and respond with a single line starting with "
                + ACCEPT_PREFIX + " if it is ready, or " + REVISE_PREFIX + " followed by specific feedback:\n\n"
                + draft;
        return callText(chatClient, prompt);
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

    private static String callText(ChatClient chatClient, String userPrompt) {
        ChatResponse response = chatClient.prompt().user(userPrompt).call().chatResponse();
        return response.getResult() == null ? null : response.getResult().getOutput().getText();
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
