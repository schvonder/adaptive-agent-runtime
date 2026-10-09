package io.adaptiveagents.topologies.chain;

import io.adaptiveagents.workload.tools.Citation;
import io.adaptiveagents.workload.tools.SearchResult;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/**
 * State for the CHAIN topology (plan &rarr; search &rarr; summarize &rarr; cite).
 *
 * <p>Backed by the LangGraph4j {@link AgentState} map so it stays checkpoint/saver compatible.
 * Accessors read the well-known keys below; {@code stage} records which node produced a failure.</p>
 */
public final class ChainState extends AgentState {

    /** The task prompt. */
    public static final String PROMPT = "prompt";

    /** Search-plan/queries text produced by the plan node. */
    public static final String PLAN = "plan";

    /** Merged search results produced by the search node. */
    public static final String SEARCH_RESULTS = "searchResults";

    /** Summary text produced by the summarize node. */
    public static final String SUMMARY = "summary";

    /** Numbered citations produced by the cite node. */
    public static final String CITATIONS = "citations";

    /** Final result JSON produced by the cite node, or {@code null} on failure. */
    public static final String RESULT = "result";

    /** Error message when the run degraded, or {@code null}. */
    public static final String ERROR = "error";

    /** True when a node failed (folded) instead of crashing. */
    public static final String DEGRADED = "degraded";

    /** Name of the node that produced the failure, or {@code null}. */
    public static final String STAGE = "stage";

    /** Set to the guard exception class name when a node boundary was refused (deadline/budget). */
    public static final String GUARD_REJECTED = "guardRejected";

    /** JSON snapshot of completed stage outputs, folded when a guard rejects a node boundary. */
    public static final String PARTIAL_RESULT = "partialResult";

    public ChainState(Map<String, Object> initData) {
        super(initData);
    }

    public String prompt() {
        return stringValue(PROMPT);
    }

    public String plan() {
        return stringValue(PLAN);
    }

    public List<SearchResult> searchResults() {
        return listValue(SEARCH_RESULTS);
    }

    public String summary() {
        return stringValue(SUMMARY);
    }

    public List<Citation> citations() {
        return listValue(CITATIONS);
    }

    public String result() {
        return stringValue(RESULT);
    }

    public String error() {
        return stringValue(ERROR);
    }

    public boolean degraded() {
        Object raw = data().get(DEGRADED);
        return raw instanceof Boolean b && b;
    }

    public String stage() {
        return stringValue(STAGE);
    }

    public String partialResult() {
        return stringValue(PARTIAL_RESULT);
    }

    private String stringValue(String key) {
        Object raw = data().get(key);
        return raw == null ? null : raw.toString();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> listValue(String key) {
        Object raw = data().get(key);
        return raw instanceof List<?> list ? (List<T>) list : List.of();
    }
}
