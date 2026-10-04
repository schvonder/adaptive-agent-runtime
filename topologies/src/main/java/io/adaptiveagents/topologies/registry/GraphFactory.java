package io.adaptiveagents.topologies.registry;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

/**
 * Seam for constructing a topology graph.
 *
 * <p>Concrete graph assembly (langgraph4j {@link StateGraph}) lives in the four factory
 * implementations. The seam returns the <em>uncompiled</em> graph so the execution runner (T16) can
 * compile it with a checkpoint saver and a per-task thread id; {@link StateGraph#compile()} (or
 * {@code compile(CompileConfig)}) is the caller's responsibility.
 */
public interface GraphFactory {
    StateGraph<? extends AgentState> graph();
}
