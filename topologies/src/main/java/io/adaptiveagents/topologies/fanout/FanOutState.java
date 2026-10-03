package io.adaptiveagents.topologies.fanout;

import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/**
 * State for the FAN_OUT/GATHER topology (3 parallel branches merged by a gather node).
 *
 * <p>Backed by the LangGraph4j {@link AgentState} map so it stays checkpoint/saver compatible.
 * {@link #SECTIONS} and {@link #FAILURES} are declared as appender channels on the graph so the
 * partial updates produced by the parallel branches merge (append) instead of overwriting each
 * other; the accessors below read the accumulated lists.</p>
 */
public final class FanOutState extends AgentState {

    /** The task prompt. */
    public static final String PROMPT = "prompt";

    /** Section texts produced by the successful branches (appender channel). */
    public static final String SECTIONS = "sections";

    /** Failure markers produced by the failed branches (appender channel). */
    public static final String FAILURES = "failures";

    /** Final merged result JSON produced by the gather node, or {@code null} on failure. */
    public static final String RESULT = "result";

    /** Error message when every branch failed, or {@code null}. */
    public static final String ERROR = "error";

    /** True when at least one branch failed. */
    public static final String DEGRADED = "degraded";

    public FanOutState(Map<String, Object> initData) {
        super(initData);
    }

    public String prompt() {
        return stringValue(PROMPT);
    }

    public List<String> sections() {
        return stringList(SECTIONS);
    }

    public List<String> failures() {
        return stringList(FAILURES);
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

    private String stringValue(String key) {
        Object raw = data().get(key);
        return raw == null ? null : raw.toString();
    }

    private List<String> stringList(String key) {
        Object raw = data().get(key);
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
