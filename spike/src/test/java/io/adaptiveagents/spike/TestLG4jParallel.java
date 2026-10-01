package io.adaptiveagents.spike;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.Test;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import java.util.Map;
import java.util.Optional;

public class TestLG4jParallel {
    @Test
    public void testParallel() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        
        graph.addNode("A", node_async(state -> {
            System.out.println("Running A");
            return Map.of("a", "done");
        }));
        graph.addNode("B1", node_async(state -> {
            System.out.println("Running B1");
            return Map.of("b1", "done");
        }));
        graph.addNode("B2", node_async(state -> {
            System.out.println("Running B2");
            return Map.of("b2", "done");
        }));
        
        graph.addEdge(StateGraph.START, "A");
        graph.addEdge("A", "B1");
        graph.addEdge("A", "B2");
        
        graph.addEdge("B1", StateGraph.END);
        graph.addEdge("B2", StateGraph.END);
        
        CompiledGraph<AgentState> compiled = graph.compile();
        Optional<AgentState> result = compiled.invoke(Map.of());
        System.out.println("RESULT: " + result.get().data());
    }
}
