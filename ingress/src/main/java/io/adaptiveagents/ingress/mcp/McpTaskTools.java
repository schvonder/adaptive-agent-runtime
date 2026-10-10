package io.adaptiveagents.ingress.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.ingress.common.EnvelopeValidator;
import io.adaptiveagents.ingress.rest.TaskIngressService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * The ENTIRE MCP surface (docs/ENVELOPE.md): three tools over the same submission pipeline
 * and store the REST/Kafka ingresses use — {@link TaskIngressService#submit(TaskEnvelope)}
 * (idempotent by {@code task_id}) and {@link TaskStore#find(UUID)}. Tool args map into the
 * SAME {@link TaskEnvelope} record (no protocol-specific fields; {@code task_type} is fixed
 * to {@code research}, the only v1 type). No MCP client, no tool federation, no MCP auth
 * extensions — the {@code /mcp} endpoint is auth-exempt per the auth matrix.
 *
 * <p>Error semantics: validation failures and unknown {@code task_id} are TOOL error results
 * (client-side errors), never server 500s — the tools throw
 * {@link io.adaptiveagents.ingress.common.TaskValidationException}/
 * {@link McpTaskNotFoundException} and the MCP server surfaces the message in an
 * {@code isError=true} call result.
 */
@Service
public class McpTaskTools {

    private final TaskIngressService ingressService;
    private final TaskStore taskStore;

    public McpTaskTools(
            @org.springframework.context.annotation.Lazy TaskIngressService ingressService,
            TaskStore taskStore) {
        this.ingressService = ingressService;
        this.taskStore = taskStore;
    }

    /** {@code submit_task} result: {@code {"task_id": "...", "status": "..."}} (status at reply time). */
    public record TaskSubmitted(
            @JsonProperty("task_id") UUID taskId,
            @JsonProperty("status") String status) {}

    /** {@code get_task} result: the full task state; {@code result}/{@code error} are raw JSON strings. */
    public record TaskState(
            @JsonProperty("task_id") UUID taskId,
            @JsonProperty("status") String status,
            @JsonProperty("topology") String topology,
            @JsonProperty("result") String result,
            @JsonProperty("error") String error) {}

    /** {@code get_task_result} result: the final result payload of a terminal task. */
    public record TaskResult(
            @JsonProperty("task_id") UUID taskId,
            @JsonProperty("status") String status,
            @JsonProperty("result") String result) {}

    @Tool(name = "submit_task", description = "Submit a research task into the adaptive agent runtime. The CLIENT "
            + "supplies task_id (UUID) — it is the idempotency key: resubmitting the same "
            + "task_id returns the existing task's current status without re-routing or "
            + "re-executing. Optional fields: breadth_hint 1..5 (default 1), deadline_ms "
            + "(duration in milliseconds, > 0), budget_usd (> 0), forced_topology "
            + "(SOLO | CHAIN | FAN_OUT | SUPERVISOR). Returns {task_id, status}.")
    public TaskSubmitted submitTask(
            @ToolParam(description = "Client-supplied task id (UUID); the idempotency key")
            UUID task_id,
            @ToolParam(description = "The research prompt")
            String prompt,
            @ToolParam(required = false, description = "Breadth hint 1..5 (default 1)")
            Integer breadth_hint,
            @ToolParam(required = false, description = "Deadline duration in milliseconds (> 0)")
            Long deadline_ms,
            @ToolParam(required = false, description = "Budget cap in USD (> 0)")
            BigDecimal budget_usd,
            @ToolParam(required = false, description = "Forced topology: SOLO, CHAIN, FAN_OUT or SUPERVISOR")
            Topology forced_topology) {
        TaskEnvelope envelope = new TaskEnvelope(
                task_id, "research", prompt,
                breadth_hint == null ? 1 : breadth_hint,
                deadline_ms, budget_usd, forced_topology);
        EnvelopeValidator.validate(envelope);
        TaskRecord record = ingressService.submit(envelope).record();
        return new TaskSubmitted(record.taskId(), record.status().name());
    }

    @Tool(name = "get_task", description = "Fetch a task's current state by id: status (SUBMITTED, ROUTED, "
            + "RUNNING, SUCCEEDED, FAILED, DEADLINE_EXCEEDED, BUDGET_EXCEEDED), chosen "
            + "topology, and the raw result/error JSON strings when present.")
    public TaskState getTask(
            @ToolParam(description = "The task id (UUID)") UUID task_id) {
        TaskRecord record = lookup(task_id);
        return new TaskState(
                record.taskId(),
                record.status().name(),
                record.topology() == null ? null : record.topology().name(),
                record.result(),
                record.error());
    }

    @Tool(name = "get_task_result", description = "Fetch the FINAL result of a completed task. Returns {task_id, "
            + "status, result} once the task reached SUCCEEDED (including degraded partial "
            + "results); yields a tool error while the task is still in flight, if it FAILED, "
            + "or if the id is unknown.")
    public TaskResult getTaskResult(
            @ToolParam(description = "The task id (UUID)") UUID task_id) {
        TaskRecord record = lookup(task_id);
        if (record.status() != TaskStatus.SUCCEEDED) {
            String reason = record.error() == null
                    ? "task is not finished yet (status " + record.status() + ")"
                    : "task ended as " + record.status() + ": " + record.error();
            throw new McpTaskNotFoundException(reason);
        }
        return new TaskResult(record.taskId(), record.status().name(), record.result());
    }

    private TaskRecord lookup(UUID taskId) {
        return taskStore.find(taskId)
                .orElseThrow(() -> new McpTaskNotFoundException("unknown task_id " + taskId));
    }
}
