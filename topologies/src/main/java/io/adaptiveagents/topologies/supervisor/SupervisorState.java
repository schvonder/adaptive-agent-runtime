package io.adaptiveagents.topologies.supervisor;

import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/**
 * State for the SUPERVISOR_TEAM topology (researcher &rarr; writer &harr; critic under supervisor routing).
 *
 * <p>Backed by the LangGraph4j {@link AgentState} map so it stays checkpoint/saver compatible. All
 * values are plain Java strings/ints/booleans (Serializable-safe, no custom records). The
 * {@link #ACCEPTED} and {@link #EVALUATED} flags drive the supervisor's pure routing decision.</p>
 */
public final class SupervisorState extends AgentState {

    /** The task prompt. */
    public static final String PROMPT = "prompt";

    /** Distilled research findings produced by the researcher node. */
    public static final String RESEARCH = "research";

    /** Report draft produced by the writer node (rewritten each revision round). */
    public static final String DRAFT = "draft";

    /** Critic verdict text produced by the critic node. */
    public static final String CRITIQUE = "critique";

    /** Number of critic evaluations performed so far (structurally capped). */
    public static final String CRITIC_ROUNDS = "criticRounds";

    /** True when the critic accepted the current draft. */
    public static final String ACCEPTED = "accepted";

    /** True when the current draft has been critiqued this round (else the draft is fresh). */
    public static final String EVALUATED = "evaluated";

    /** True once the run finished (clean or degraded). */
    public static final String FINISHED = "finished";

    /** True when the run degraded (a node folded, or forced finish after the critic cap). */
    public static final String DEGRADED = "degraded";

    /** Set to the guard exception class name when a node boundary was refused (deadline/budget). */
    public static final String GUARD_REJECTED = "guardRejected";

    /** JSON snapshot of the team artifact so far, folded when a guard rejects a node boundary. */
    public static final String PARTIAL_RESULT = "partialResult";

    /** Final merged result JSON produced by the finalize node, or {@code null} on failure. */
    public static final String RESULT = "result";

    /** Error message when a node folded, or {@code null}. */
    public static final String ERROR = "error";

    public SupervisorState(Map<String, Object> initData) {
        super(initData);
    }

    public String prompt() {
        return stringValue(PROMPT);
    }

    public String research() {
        return stringValue(RESEARCH);
    }

    public String draft() {
        return stringValue(DRAFT);
    }

    public String critique() {
        return stringValue(CRITIQUE);
    }

    public int criticRounds() {
        Object raw = data().get(CRITIC_ROUNDS);
        return raw instanceof Number n ? n.intValue() : 0;
    }

    public boolean accepted() {
        return boolValue(ACCEPTED);
    }

    public boolean evaluated() {
        return boolValue(EVALUATED);
    }

    public boolean finished() {
        return boolValue(FINISHED);
    }

    public boolean degraded() {
        return boolValue(DEGRADED);
    }

    public String result() {
        return stringValue(RESULT);
    }

    public String error() {
        return stringValue(ERROR);
    }

    public String partialResult() {
        return stringValue(PARTIAL_RESULT);
    }

    private String stringValue(String key) {
        Object raw = data().get(key);
        return raw == null ? null : raw.toString();
    }

    private boolean boolValue(String key) {
        Object raw = data().get(key);
        return raw instanceof Boolean b && b;
    }
}
