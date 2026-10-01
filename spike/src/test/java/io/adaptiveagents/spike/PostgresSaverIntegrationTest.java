package io.adaptiveagents.spike;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.StateGraph.START;

@Testcontainers
class PostgresSaverIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    HikariDataSource dataSource;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
    }

    @org.junit.jupiter.api.AfterEach
    void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void parallelBranchCheckpointsAreConsistent() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

        PostgresSaver saver = PostgresSaver.builder()
            .datasource(dataSource)
            .stateSerializer(graph.getStateSerializer())
            .createTables(true)
            .build();
        
        NodeAction<AgentState> nodeA = state -> Map.of("step", "A");
        NodeAction<AgentState> nodeB1 = state -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            return Map.of("branch1", "B1");
        };
        NodeAction<AgentState> nodeB2 = state -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            return Map.of("branch2", "B2");
        };
        
        graph.addNode("A", node_async(nodeA));
        graph.addNode("B1", node_async(nodeB1));
        graph.addNode("B2", node_async(nodeB2));
        
        graph.addEdge(START, "A");
        graph.addEdge("A", "B1");
        graph.addEdge("A", "B2");
        graph.addEdge("B1", org.bsc.langgraph4j.StateGraph.END);
        graph.addEdge("B2", org.bsc.langgraph4j.StateGraph.END);

        org.bsc.langgraph4j.CompileConfig compileConfig = org.bsc.langgraph4j.CompileConfig.builder()
            .checkpointSaver(saver)
            .build();
            
        CompiledGraph<AgentState> compiled = graph.compile(compileConfig);

        RunnableConfig config = RunnableConfig.builder().threadId("thread-parallel").build();
        
        Optional<AgentState> resultOpt = compiled.invoke(Map.of(), config);
        
        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get().data()).containsEntry("step", "A");
        assertThat(resultOpt.get().data()).containsEntry("branch1", "B1");
        assertThat(resultOpt.get().data()).containsEntry("branch2", "B2");
    }

    @Test
    void resumeFromCheckpointSkipsCompletedNodes() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

        PostgresSaver saver = PostgresSaver.builder()
            .datasource(dataSource)
            .stateSerializer(graph.getStateSerializer())
            .createTables(true)
            .build();
        
        java.util.concurrent.atomic.AtomicInteger node1Count = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicBoolean failNode2 = new java.util.concurrent.atomic.AtomicBoolean(true);
        
        NodeAction<AgentState> node1 = state -> {
            node1Count.incrementAndGet();
            return Map.of("step", "1");
        };
        NodeAction<AgentState> node2 = state -> {
            if (failNode2.get()) {
                throw new RuntimeException("Simulated failure");
            }
            return Map.of("step", "2");
        };
        
        graph.addNode("node1", node_async(node1));
        graph.addNode("node2", node_async(node2));
        
        graph.addEdge(START, "node1");
        graph.addEdge("node1", "node2");
        graph.addEdge("node2", org.bsc.langgraph4j.StateGraph.END);

        org.bsc.langgraph4j.CompileConfig compileConfig = org.bsc.langgraph4j.CompileConfig.builder()
            .checkpointSaver(saver)
            .build();
            
        CompiledGraph<AgentState> compiled = graph.compile(compileConfig);
        RunnableConfig config = RunnableConfig.builder().threadId("thread-resume").build();
        
        try {
            compiled.invoke(Map.of(), config);
        } catch (Exception e) {
        }
        
        assertThat(node1Count.get()).isEqualTo(1);
        
        failNode2.set(false);
        
        Optional<AgentState> resultOpt = compiled.invoke((Map<String, Object>) null, config);
        
        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get().data()).containsEntry("step", "2");
        assertThat(node1Count.get()).isEqualTo(1);
    }
}
