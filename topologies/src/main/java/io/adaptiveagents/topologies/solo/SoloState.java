package io.adaptiveagents.topologies.solo;

import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/**
 * State for the SOLO topology (a single ReAct agent).
 *
 * <p>Backed by the LangGraph4j {@link AgentState} map so it stays checkpoint/saver compatible
 * (the postgres saver serializes the underlying map, not a custom type). The convenience accessors
 * read the well-known keys below.</p>
 */
public final class SoloState extends AgentState {

    /** The task prompt fed to the agent. */
    public static final String PROMPT = "prompt";

    /** Ordered log of tool/iteration activity (human-readable strings). */
    public static final String TOOL_TRANSCRIPT = "toolTranscript";

    /** Final answer text produced by the model, or {@code null} while unresolved. */
    public static final String RESULT = "result";

    /** Error message when the run degraded, or {@code null}. */
    public static final String ERROR = "error";

    /** True when the run did not produce a clean result (tool failure / loop exhaustion). */
    public static final String DEGRADED = "degraded";

    /** Set to the guard exception class name when a node boundary was refused (deadline/budget). */
    public static final String GUARD_REJECTED = "guardRejected";

    public SoloState(Map<String, Object> initData) {
        super(initData);
    }

    public String prompt() {
        return stringValue(PROMPT);
    }

    public List<String> toolTranscript() {
        Object raw = data().get(TOOL_TRANSCRIPT);
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
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
}
