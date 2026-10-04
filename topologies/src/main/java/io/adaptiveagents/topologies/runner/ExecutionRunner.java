package io.adaptiveagents.topologies.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.IllegalTaskStateException;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.guard.BudgetExceededException;
import io.adaptiveagents.topologies.guard.DeadlineExceededException;
import io.adaptiveagents.topologies.registry.TopologyRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checkpointed execution of a routed task's topology graph, with budget/deadline guard mapping.
 *
 * <p>Lives in {@code topologies} (not {@code core}) because it depends on the topology graph
 * factories, which are a {@code topologies} concern — the same deviation class as the
 * Topology-enum-in-core decision recorded for earlier tasks.</p>
 *
 * <p>{@link #execute(UUID)} advances a ROUTED task to RUNNING, compiles its graph with a
 * {@link PostgresSaver} checkpoint saver and the task id as the LangGraph4j thread id, then invokes
 * it. {@link #resume(UUID)} re-invokes the same compiled graph (same thread id) with a {@code null}
 * input so langgraph4j resumes from the last checkpoint, skipping completed nodes.</p>
 *
 * <p>Both run the graph invoke on an unpooled virtual-thread executor and block until it completes.
 * The final state maps to a terminal status in precedence order: a {@code guardRejected} value of
 * {@code DeadlineExceededException}/{@code BudgetExceededException} becomes
 * {@code DEADLINE_EXCEEDED}/{@code BUDGET_EXCEEDED}; a folded error (topology-level failure)
 * becomes {@code FAILED}; otherwise a non-null result becomes {@code SUCCEEDED}. In-flight LLM
 * calls are not interrupted — guards fire at node boundaries only; node-level future timeouts land
 * in T19/T20. Nodes stay persistence-free — the runner alone owns
 * {@link TaskStore}/{@link ProgressService}.</p>
 */
@Component
public final class ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROMPT_KEY = "prompt";
    private static final String RESULT_KEY = "result";
    private static final String ERROR_KEY = "error";
    private static final String GUARD_REJECTED_KEY = "guardRejected";

    private static final String NODE_START = "NODE_START";
    private static final String TASK_COMPLETED = "TASK_COMPLETED";
    private static final String TASK_FAILED = "TASK_FAILED";
    private static final String TASK_DEADLINE_EXCEEDED = "TASK_DEADLINE_EXCEEDED";
    private static final String TASK_BUDGET_EXCEEDED = "TASK_BUDGET_EXCEEDED";

    /** Unpooled virtual-thread executor for graph invokes. Never pooled. */
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    private final TopologyRegistry topologyRegistry;
    private final TaskStore taskStore;
    private final ProgressService progressService;
    private final DataSource dataSource;

    public ExecutionRunner(TopologyRegistry topologyRegistry, TaskStore taskStore, ProgressService progressService, DataSource dataSource) {
        this.topologyRegistry = topologyRegistry;
        this.taskStore = taskStore;
        this.progressService = progressService;
        this.dataSource = dataSource;
    }

    /**
     * Terminal outcome of a run: the status persisted for the task plus the payload written to the
     * {@code result}/{@code error} column ({@code null} detail only when nothing was writable).
     */
    public record Outcome(TaskStatus status, String detail) {}

    /**
     * Runs a routed task to completion (blocking; the graph invoke always executes on an unpooled
     * virtual thread).
     *
     * @param taskId id of a task whose status is ROUTED
     * @throws IllegalTaskStateException when the task is missing or not ROUTED
     */
    public Outcome execute(UUID taskId) {
        TaskRecord task = requireStatus(taskId, TaskStatus.ROUTED);
        taskStore.transition(taskId, TaskStatus.RUNNING);
        return runAndMap(taskId, task, false);
    }

    /** Async wrapper over {@link #execute(UUID)} on the unpooled virtual-thread executor. */
    public CompletableFuture<Outcome> executeAsync(UUID taskId) {
        return CompletableFuture.supplyAsync(() -> execute(taskId), VIRTUAL);
    }

    /**
     * Resumes a task left RUNNING from a previous attempt (blocking), continuing from its last
     * checkpoint: the graph is re-invoked with the same thread id and a {@code null} input, so
     * langgraph4j replays only the pending nodes.
     *
     * @param taskId id of a task whose status is RUNNING
     * @throws IllegalTaskStateException when the task is missing or not RUNNING
     */
    public Outcome resume(UUID taskId) {
        TaskRecord task = requireStatus(taskId, TaskStatus.RUNNING);
        return runAndMap(taskId, task, true);
    }

    /** Async wrapper over {@link #resume(UUID)} on the unpooled virtual-thread executor. */
    public CompletableFuture<Outcome> resumeAsync(UUID taskId) {
        return CompletableFuture.supplyAsync(() -> resume(taskId), VIRTUAL);
    }

    private TaskRecord requireStatus(UUID taskId, TaskStatus required) {
        TaskRecord task = taskStore.find(taskId)
                .orElseThrow(() -> new IllegalTaskStateException("Task not found: " + taskId));
        if (task.status() != required) {
            throw new IllegalTaskStateException("Task " + taskId + " is " + task.status() + ", expected " + required);
        }
        if (task.topology() == null) {
            throw new IllegalTaskStateException("Task " + taskId + " has no recorded topology");
        }
        return task;
    }

    private Outcome runAndMap(UUID taskId, TaskRecord task, boolean resume) {
        try {
            Map<String, Object> data = VIRTUAL.submit(() -> invokeGraph(task, resume)).get();
            return mapOutcome(taskId, data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running task " + taskId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Task {} graph invoke threw: {}", taskId, cause.getMessage());
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            taskStore.completeTerminal(taskId, TaskStatus.FAILED, errorJson(message));
            progressService.publish(taskId, TASK_FAILED, reasonPayload(message));
            return new Outcome(TaskStatus.FAILED, message);
        }
    }

    private Map<String, Object> invokeGraph(TaskRecord task, boolean resume) {
        StateGraph<? extends AgentState> graph = topologyRegistry.graphFor(task.topology());
        CompiledGraph<? extends AgentState> compiled = compile(graph);
        RunnableConfig config = RunnableConfig.builder().threadId(task.taskId().toString()).build();
        Map<String, Object> input = resume ? null : Map.of(PROMPT_KEY, task.envelope().prompt());
        return compiled.invoke(input, config)
                .map(AgentState::data)
                .orElseThrow(() -> new IllegalStateException(
                        "Graph produced no final state for task " + task.taskId()));
    }

    private CompiledGraph<? extends AgentState> compile(StateGraph<? extends AgentState> graph) {
        try {
            PostgresSaver.Builder builder = PostgresSaver.builder()
                    .datasource(dataSource)
                    .stateSerializer(graph.getStateSerializer())
                    .createTables(true);
            PostgresSaver saver = new LeakFreePostgresSaver(builder);
            return graph.compile(CompileConfig.builder().checkpointSaver(saver).build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile graph with checkpoint saver", e);
        }
    }

    /**
     * Maps the final graph state to a terminal task status. Order matters: a guard rejection wins
     * (deadline/budget), then a folded topology-level failure (error set — e.g. a Supervisor fold,
     * which still carries a partial result json), and only then a clean result. A partial result
     * alongside an error is therefore FAILED, not SUCCEEDED.
     */
    private Outcome mapOutcome(UUID taskId, Map<String, Object> data) {
        Object guardRejected = data.get(GUARD_REJECTED_KEY);
        if (guardRejected != null) {
            String guard = guardRejected.toString();
            String error = stringOf(data.get(ERROR_KEY));
            if (DeadlineExceededException.class.getSimpleName().equals(guard)) {
                taskStore.completeTerminal(taskId, TaskStatus.DEADLINE_EXCEEDED, errorJson(error));
                progressService.publish(taskId, TASK_DEADLINE_EXCEEDED, reasonPayload(error));
                return new Outcome(TaskStatus.DEADLINE_EXCEEDED, error);
            }
            if (BudgetExceededException.class.getSimpleName().equals(guard)) {
                taskStore.completeTerminal(taskId, TaskStatus.BUDGET_EXCEEDED, errorJson(error));
                progressService.publish(taskId, TASK_BUDGET_EXCEEDED, reasonPayload(error));
                return new Outcome(TaskStatus.BUDGET_EXCEEDED, error);
            }
            taskStore.completeTerminal(taskId, TaskStatus.FAILED, errorJson(error));
            progressService.publish(taskId, TASK_FAILED, reasonPayload(error));
            return new Outcome(TaskStatus.FAILED, error);
        }

        String error = stringOf(data.get(ERROR_KEY));
        if (data.get(ERROR_KEY) != null) {
            taskStore.completeTerminal(taskId, TaskStatus.FAILED, errorJson(error));
            progressService.publish(taskId, TASK_FAILED, reasonPayload(error));
            return new Outcome(TaskStatus.FAILED, error);
        }

        Object result = data.get(RESULT_KEY);
        if (result != null) {
            String resultJson = normalizeJson(result.toString());
            taskStore.completeTerminal(taskId, TaskStatus.SUCCEEDED, resultJson);
            progressService.publish(taskId, TASK_COMPLETED, reasonPayload(resultJson));
            return new Outcome(TaskStatus.SUCCEEDED, resultJson);
        }

        taskStore.completeTerminal(taskId, TaskStatus.FAILED, errorJson(error));
        progressService.publish(taskId, TASK_FAILED, reasonPayload(error));
        return new Outcome(TaskStatus.FAILED, error);
    }

    private static String stringOf(Object value) {
        return value == null ? "unknown error" : value.toString();
    }

    /** Returns the value as a JSON document string, wrapping raw text in a JSON string literal. */
    private String normalizeJson(String value) {
        try {
            MAPPER.readTree(value);
            return value;
        } catch (Exception e) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (Exception e2) {
                return value;
            }
        }
    }

    private String errorJson(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("message", message);
        return node.toString();
    }

    private ObjectNode reasonPayload(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("reason", message);
        return node;
    }
}
