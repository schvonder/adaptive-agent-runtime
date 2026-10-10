package io.adaptiveagents.topologies.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.progress.ProgressService;
import io.adaptiveagents.core.store.IllegalTaskStateException;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.core.trace.TaskTraceContext;
import io.adaptiveagents.topologies.guard.BudgetExceededException;
import io.adaptiveagents.topologies.guard.DeadlineExceededException;
import io.adaptiveagents.topologies.registry.TopologyRegistry;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * input so langgraph4j resumes from the last checkpoint, skipping completed nodes.
 * {@link #restart(UUID)} serves a RUNNING task whose thread has <em>no</em> checkpoints yet (seeded
 * RUNNING but never executed — verified empirically: a {@code null}-input invoke on a
 * checkpoint-less thread throws {@code Resume request without a valid checkpoint!}): it invokes
 * with the envelope-derived initial input but, unlike {@code execute}, does not re-transition the
 * status, which is already RUNNING. {@link #startOrResumeAsync(UUID)} picks between the two by
 * checkpoint presence for startup rescan.</p>
 *
 * <p>Both run the graph invoke on an unpooled virtual-thread executor and block until it completes.
 * The final state maps to a terminal status in precedence order: a {@code guardRejected} value of
 * {@code DeadlineExceededException}/{@code BudgetExceededException} becomes
 * {@code DEADLINE_EXCEEDED}/{@code BUDGET_EXCEEDED}; a folded error (topology-level failure)
 * becomes {@code FAILED}; otherwise a non-null result becomes {@code SUCCEEDED}. Nodes stay
 * persistence-free — the runner and the deadline sweep alone own
 * {@link TaskStore}/{@link ProgressService}.</p>
 *
 * <p><strong>Deadline guard, no zombies.</strong> Boundary guards stop a run between nodes; the
 * runtime sweep ({@code RunningTaskSweep}) stops one <em>mid-node</em>, where no boundary ever
 * fires. Every graph invoke is registered in an in-flight registry keyed by task id before it is
 * handed to the executor, and deregistered in a {@code finally} once its {@code get()} returns or
 * throws. {@link #cancel(UUID)} removes the entry and interrupts the worker thread; the resulting
 * {@link CancellationException} — caught ahead of {@link ExecutionException}, of which it is not
 * a subtype — is mapped to {@code DEADLINE_EXCEEDED} and the {@code executeAsync} future then
 * completes normally with that outcome (never a zombie run future). Finalization ownership per
 * path: a boundary-guard rejection is finalized by the outcome mapping; a sweep cancel by the
 * runner's cancellation branch; a RUNNING row with no local run (abandoned or foreign) directly
 * by the sweep. The two finalizers are idempotent against each other: the optimistic-locked
 * terminal transition lets exactly one win and the loser absorbs
 * {@link IllegalTaskStateException}.</p>
 */
@Component
public final class ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROMPT_KEY = "prompt";
    private static final String RESULT_KEY = "result";
    private static final String ERROR_KEY = "error";
    private static final String GUARD_REJECTED_KEY = "guardRejected";
    private static final String PARTIAL_RESULT_KEY = "partialResult";

    private static final String NODE_START = "NODE_START";
    private static final String TASK_COMPLETED = "TASK_COMPLETED";
    private static final String TASK_FAILED = "TASK_FAILED";
    private static final String TASK_DEADLINE_EXCEEDED = "TASK_DEADLINE_EXCEEDED";
    private static final String TASK_BUDGET_EXCEEDED = "TASK_BUDGET_EXCEEDED";

    /** Unpooled virtual-thread executor for graph invokes. Never pooled. */
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * In-flight graph runs keyed by task id: registered before a run is handed to the executor,
     * deregistered when its {@code get()} returns or throws (or when {@link #cancel(UUID)}
     * removes the entry). This registry is the sweep's cancel surface.
     */
    private final ConcurrentHashMap<UUID, Future<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Span> activeTaskSpans = new ConcurrentHashMap<>();

    private final TopologyRegistry topologyRegistry;
    private final TaskStore taskStore;
    private final ProgressService progressService;
    private final DataSource dataSource;
    private final ObjectProvider<Tracer> tracerProvider;

    @Autowired
    public ExecutionRunner(
            TopologyRegistry topologyRegistry,
            TaskStore taskStore,
            ProgressService progressService,
            DataSource dataSource,
            ObjectProvider<Tracer> tracerProvider) {
        this.topologyRegistry = topologyRegistry;
        this.taskStore = taskStore;
        this.progressService = progressService;
        this.dataSource = dataSource;
        this.tracerProvider = tracerProvider;
    }

    public ExecutionRunner(
            TopologyRegistry topologyRegistry,
            TaskStore taskStore,
            ProgressService progressService,
            DataSource dataSource) {
        this(topologyRegistry, taskStore, progressService, dataSource, null);
    }

    private Tracer getTracer() {
        return tracerProvider != null ? tracerProvider.getIfAvailable() : null;
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
        Tracer tracer = getTracer();
        if (tracer != null && tracer.currentSpan() == null) {
            Span taskSpan = tracer.nextSpan().name("task-execution").tag("task_id", taskId.toString()).start();
            activeTaskSpans.put(taskId, taskSpan);
            BaggageInScope baggage = tracer.createBaggageInScope("task_id", taskId.toString());
            TaskTraceContext.setTaskId(taskId);
            try (Tracer.SpanInScope ws = tracer.withSpan(taskSpan)) {
                return executeInternal(taskId);
            } catch (Throwable t) {
                taskSpan.error(t);
                throw t;
            } finally {
                activeTaskSpans.remove(taskId);
                if (baggage != null) {
                    baggage.close();
                }
                taskSpan.end();
                TaskTraceContext.clear();
            }
        }
        return executeInternal(taskId);
    }

    private Outcome executeInternal(UUID taskId) {
        TaskRecord task = requireStatus(taskId, TaskStatus.ROUTED);
        taskStore.transition(taskId, TaskStatus.RUNNING);
        return runAndMap(taskId, task, initialState(task));
    }

    /** Async wrapper over {@link #execute(UUID)} on the unpooled virtual-thread executor. */
    public CompletableFuture<Outcome> executeAsync(UUID taskId) {
        Tracer tracer = getTracer();
        Span parentSpan = tracer != null ? tracer.currentSpan() : null;
        return CompletableFuture.supplyAsync(() -> {
            Span taskSpan = null;
            BaggageInScope baggage = null;
            if (tracer != null) {
                taskSpan = (parentSpan != null)
                        ? tracer.nextSpan(parentSpan).name("task-execution").tag("task_id", taskId.toString())
                        : tracer.nextSpan().name("task-execution").tag("task_id", taskId.toString());
                taskSpan.start();
                activeTaskSpans.put(taskId, taskSpan);
                baggage = tracer.createBaggageInScope("task_id", taskId.toString());
            }
            TaskTraceContext.setTaskId(taskId);
            if (taskSpan != null) {
                try (Tracer.SpanInScope ws = tracer.withSpan(taskSpan)) {
                    return executeInternal(taskId);
                } catch (Throwable t) {
                    taskSpan.error(t);
                    throw t;
                } finally {
                    activeTaskSpans.remove(taskId);
                    if (baggage != null) {
                        baggage.close();
                    }
                    taskSpan.end();
                    TaskTraceContext.clear();
                }
            } else {
                try {
                    return executeInternal(taskId);
                } finally {
                    TaskTraceContext.clear();
                }
            }
        }, VIRTUAL);
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
        Tracer tracer = getTracer();
        if (tracer != null && tracer.currentSpan() == null) {
            Span taskSpan = tracer.nextSpan().name("task-execution").tag("task_id", taskId.toString()).start();
            activeTaskSpans.put(taskId, taskSpan);
            BaggageInScope baggage = tracer.createBaggageInScope("task_id", taskId.toString());
            TaskTraceContext.setTaskId(taskId);
            try (Tracer.SpanInScope ws = tracer.withSpan(taskSpan)) {
                return resumeInternal(taskId);
            } catch (Throwable t) {
                taskSpan.error(t);
                throw t;
            } finally {
                activeTaskSpans.remove(taskId);
                if (baggage != null) {
                    baggage.close();
                }
                taskSpan.end();
                TaskTraceContext.clear();
            }
        }
        return resumeInternal(taskId);
    }

    private Outcome resumeInternal(UUID taskId) {
        TaskRecord task = requireStatus(taskId, TaskStatus.RUNNING);
        return runAndMap(taskId, task, null);
    }

    /** Async wrapper over {@link #resume(UUID)} on the unpooled virtual-thread executor. */
    public CompletableFuture<Outcome> resumeAsync(UUID taskId) {
        Tracer tracer = getTracer();
        Span parentSpan = tracer != null ? tracer.currentSpan() : null;
        return CompletableFuture.supplyAsync(() -> {
            Span taskSpan = null;
            BaggageInScope baggage = null;
            if (tracer != null) {
                taskSpan = (parentSpan != null)
                        ? tracer.nextSpan(parentSpan).name("task-execution").tag("task_id", taskId.toString())
                        : tracer.nextSpan().name("task-execution").tag("task_id", taskId.toString());
                taskSpan.start();
                activeTaskSpans.put(taskId, taskSpan);
                baggage = tracer.createBaggageInScope("task_id", taskId.toString());
            }
            TaskTraceContext.setTaskId(taskId);
            if (taskSpan != null) {
                try (Tracer.SpanInScope ws = tracer.withSpan(taskSpan)) {
                    return resumeInternal(taskId);
                } catch (Throwable t) {
                    taskSpan.error(t);
                    throw t;
                } finally {
                    activeTaskSpans.remove(taskId);
                    if (baggage != null) {
                        baggage.close();
                    }
                    taskSpan.end();
                    TaskTraceContext.clear();
                }
            } else {
                try {
                    return resumeInternal(taskId);
                } finally {
                    TaskTraceContext.clear();
                }
            }
        }, VIRTUAL);
    }

    /**
     * Runs a RUNNING task that has never checkpointed (blocking): the graph is invoked with the
     * envelope-derived initial input — the same invocation shape as {@link #execute(UUID)} minus
     * the ROUTED→RUNNING transition, which the caller has already performed.
     *
     * @param taskId id of a task whose status is RUNNING and whose checkpoint thread is empty
     * @throws IllegalTaskStateException when the task is missing or not RUNNING
     */
    public Outcome restart(UUID taskId) {
        Tracer tracer = getTracer();
        if (tracer != null && tracer.currentSpan() == null) {
            Span taskSpan = tracer.nextSpan().name("task-execution").tag("task_id", taskId.toString()).start();
            activeTaskSpans.put(taskId, taskSpan);
            BaggageInScope baggage = tracer.createBaggageInScope("task_id", taskId.toString());
            TaskTraceContext.setTaskId(taskId);
            try (Tracer.SpanInScope ws = tracer.withSpan(taskSpan)) {
                return restartInternal(taskId);
            } catch (Throwable t) {
                taskSpan.error(t);
                throw t;
            } finally {
                activeTaskSpans.remove(taskId);
                if (baggage != null) {
                    baggage.close();
                }
                taskSpan.end();
                TaskTraceContext.clear();
            }
        }
        return restartInternal(taskId);
    }

    private Outcome restartInternal(UUID taskId) {
        TaskRecord task = requireStatus(taskId, TaskStatus.RUNNING);
        return runAndMap(taskId, task, initialState(task));
    }

    /**
     * Async wrapper over {@link #restart(UUID)} on the unpooled virtual-thread executor.
     */
    public CompletableFuture<Outcome> restartAsync(UUID taskId) {
        Tracer tracer = getTracer();
        Span parentSpan = tracer != null ? tracer.currentSpan() : null;
        return CompletableFuture.supplyAsync(() -> {
            Span taskSpan = null;
            BaggageInScope baggage = null;
            if (tracer != null) {
                taskSpan = (parentSpan != null)
                        ? tracer.nextSpan(parentSpan).name("task-execution").tag("task_id", taskId.toString())
                        : tracer.nextSpan().name("task-execution").tag("task_id", taskId.toString());
                taskSpan.start();
                activeTaskSpans.put(taskId, taskSpan);
                baggage = tracer.createBaggageInScope("task_id", taskId.toString());
            }
            TaskTraceContext.setTaskId(taskId);
            if (taskSpan != null) {
                try (Tracer.SpanInScope ws = tracer.withSpan(taskSpan)) {
                    return restartInternal(taskId);
                } catch (Throwable t) {
                    taskSpan.error(t);
                    throw t;
                } finally {
                    activeTaskSpans.remove(taskId);
                    if (baggage != null) {
                        baggage.close();
                    }
                    taskSpan.end();
                    TaskTraceContext.clear();
                }
            } else {
                try {
                    return restartInternal(taskId);
                } finally {
                    TaskTraceContext.clear();
                }
            }
        }, VIRTUAL);
    }

    /**
     * Startup-rescan entry point: dispatches a RUNNING task by checkpoint presence — with
     * checkpoints it resumes from the last one, without them (seeded RUNNING but never executed)
     * it restarts with a fresh initial input. Fire-and-forget on the unpooled virtual-thread
     * executor; graph-level failures fold to FAILED through the runner's outcome mapping.
     */
    public CompletableFuture<Outcome> startOrResumeAsync(UUID taskId) {
        return hasCheckpoints(taskId) ? resumeAsync(taskId) : restartAsync(taskId);
    }

    /**
     * Cancels the in-flight graph run for {@code taskId}, interrupting its worker thread
     * ({@code mayInterrupt = true}), and reports whether an in-flight run was found. Both a
     * successful cancel and a run that completed in the interim race count as found: the caller
     * treats {@code true} as "the runner side settles the outcome” — either the cancellation
     * branch finalizes {@code DEADLINE_EXCEEDED}, or the run finalized on its own moments
     * earlier. {@code false} means no local run exists for the task (row left RUNNING by an
     * abandoned or foreign run) and the caller must finalize directly.
     */
    public boolean cancel(UUID taskId) {
        Future<Map<String, Object>> run = inFlight.remove(taskId);
        if (run == null) {
            return false;
        }
        boolean mayInterruptIfRunning = true;
        run.cancel(mayInterruptIfRunning);
        return true;
    }

    /**
     * Number of graph runs currently in flight. The executor-side no-zombie assertion surface:
     * a value of zero after a task reaches a terminal status means no run future is still parked
     * in {@link #runAndMap} (the ThreadMXBean-style thread-baseline check, as an executor assert).
     */
    public int inFlightCount() {
        return inFlight.size();
    }

    private boolean hasCheckpoints(UUID taskId) {
        // The saver stores the task id as thread_name; see LeakFreePostgresSaver for the join. A
        // missing saver table means no graph ever ran here, which is the restart path.
        String sql = "SELECT COUNT(*) FROM lg4jcheckpoint c "
                + "JOIN lg4jthread t ON t.thread_id = c.thread_id WHERE t.thread_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
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

    private Outcome runAndMap(UUID taskId, TaskRecord task, Map<String, Object> input) {
        // Registered BEFORE the run is handed to the executor: a cancel arriving at any instant
        // after registration finds the entry (a submit()-then-put() shape would leave a window
        // in which the sweep misses the run and finalizes underneath it).
        Tracer tracer = getTracer();
        Span currentSpan = tracer != null ? tracer.currentSpan() : null;
        FutureTask<Map<String, Object>> run = new FutureTask<>(() -> {
            if (tracer != null && currentSpan != null) {
                try (Tracer.SpanInScope ws = tracer.withSpan(currentSpan)) {
                    return invokeGraph(task, input);
                }
            }
            return invokeGraph(task, input);
        });
        inFlight.put(taskId, run);
        try {
            VIRTUAL.execute(run);
            Map<String, Object> data = run.get();
            return mapOutcome(taskId, data);
        } catch (CancellationException e) {
            return cancelledOutcome(taskId, e);
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
        } finally {
            // Two-arg remove: evict only this run's entry, never a newer run registered for the
            // same task id in the interim.
            inFlight.remove(taskId, run);
        }
    }

    /**
     * Terminal mapping for a cancelled run (deadline sweep): {@code DEADLINE_EXCEEDED},
     * published and persisted, then a normal outcome — never a rethrow, so the caller's
     * {@code executeAsync} future completes instead of leaking as a zombie. Idempotent against
     * any other finalizer (sweep-direct on a no-local-run misread, boundary guard, foreign
     * process): the optimistic-locked terminal transition rejects the second writer with
     * {@link IllegalTaskStateException}, which is absorbed here at info — the task is already
     * terminal, which is all this path wants.
     */
    private Outcome cancelledOutcome(UUID taskId, CancellationException error) {
        if (carriesInterrupt(error)) {
            Thread.currentThread().interrupt();
        }
        String reason = "cancelled by deadline sweep";
        try {
            taskStore.completeTerminal(taskId, TaskStatus.DEADLINE_EXCEEDED, errorJson(reason));
            progressService.publish(taskId, TASK_DEADLINE_EXCEEDED, reasonPayload(reason));
        } catch (IllegalTaskStateException alreadyFinalized) {
            log.info("Task {} finalized elsewhere while its run was cancelled (absorbed): {}",
                    taskId, alreadyFinalized.getMessage());
        }
        return new Outcome(TaskStatus.DEADLINE_EXCEEDED, reason);
    }

    private static boolean carriesInterrupt(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> invokeGraph(TaskRecord task, Map<String, Object> input) {
        StateGraph<? extends AgentState> graph = topologyRegistry.graphFor(task.topology());
        attachNodeTracing(graph, task);
        CompiledGraph<? extends AgentState> compiled = compile(graph);
        RunnableConfig config = RunnableConfig.builder().threadId(task.taskId().toString()).build();
        return compiled.invoke(input, config)
                .map(AgentState::data)
                .orElseThrow(() -> new IllegalStateException(
                        "Graph produced no final state for task " + task.taskId()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void attachNodeTracing(StateGraph<? extends AgentState> graph, TaskRecord task) {
        Tracer tracer = getTracer();
        if (tracer == null) {
            return;
        }
        ((StateGraph) graph).addWrapCallNodeHook((nodeId, state, config, next) -> {
            Span parent = activeTaskSpans.get(task.taskId());
            Span nodeSpan;
            if (parent != null) {
                nodeSpan = tracer.nextSpan(parent);
            } else {
                Span cur = tracer.currentSpan();
                nodeSpan = cur != null ? tracer.nextSpan(cur) : tracer.nextSpan();
            }
            nodeSpan.name((String) nodeId)
                    .tag("task_id", task.taskId().toString())
                    .tag("node", (String) nodeId)
                    .tag("topology", task.topology().name())
                    .start();
            TaskTraceContext.setTaskId(task.taskId());
            try (Tracer.SpanInScope ws = tracer.withSpan(nodeSpan)) {
                CompletableFuture<Map<String, Object>> future = ((AsyncNodeActionWithConfig) next).apply((AgentState) state, (RunnableConfig) config);
                return future.whenComplete((res, err) -> {
                    try {
                        if (err != null) {
                            nodeSpan.error(err);
                        }
                    } finally {
                        nodeSpan.end();
                    }
                });
            } catch (Throwable t) {
                nodeSpan.error(t);
                nodeSpan.end();
                throw t;
            } finally {
                TaskTraceContext.clear();
            }
        });
    }

    /** A {@code null} input means "resume from the last checkpoint" in the langgraph4j invoke. */
    private static Map<String, Object> initialState(TaskRecord task) {
        return Map.of(PROMPT_KEY, task.envelope().prompt());
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
     * alongside an error is therefore FAILED, not SUCCEEDED. A guard rejection never drops the
     * partial result the topology folded: it is embedded as {@code partial} in the error payload
     * alongside the {@code message}.
     */
    private Outcome mapOutcome(UUID taskId, Map<String, Object> data) {
        Object guardRejected = data.get(GUARD_REJECTED_KEY);
        if (guardRejected != null) {
            String guard = guardRejected.toString();
            String error = stringOf(data.get(ERROR_KEY));
            String payload = guardErrorJson(error, data.get(PARTIAL_RESULT_KEY));
            if (DeadlineExceededException.class.getSimpleName().equals(guard)) {
                taskStore.completeTerminal(taskId, TaskStatus.DEADLINE_EXCEEDED, payload);
                progressService.publish(taskId, TASK_DEADLINE_EXCEEDED, reasonPayload(error));
                return new Outcome(TaskStatus.DEADLINE_EXCEEDED, error);
            }
            if (BudgetExceededException.class.getSimpleName().equals(guard)) {
                taskStore.completeTerminal(taskId, TaskStatus.BUDGET_EXCEEDED, payload);
                progressService.publish(taskId, TASK_BUDGET_EXCEEDED, reasonPayload(error));
                return new Outcome(TaskStatus.BUDGET_EXCEEDED, error);
            }
            taskStore.completeTerminal(taskId, TaskStatus.FAILED, payload);
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

    /**
     * Error payload for a guard rejection: {@code {"message": ..., "partial": ...}}. The partial is
     * the JSON snapshot the topology folded (topology-specific state key {@code partialResult});
     * a non-JSON partial is embedded as a plain string so nothing is dropped.
     */
    private String guardErrorJson(String message, Object partial) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("message", message);
        if (partial != null) {
            try {
                node.set("partial", MAPPER.readTree(partial.toString()));
            } catch (Exception e) {
                node.put("partial", partial.toString());
            }
        }
        return node.toString();
    }

    private ObjectNode reasonPayload(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("reason", message);
        return node;
    }
}
