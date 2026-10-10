package io.adaptiveagents.ingress.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.store.TaskRecord;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.ingress.common.EnvelopeValidator;
import io.adaptiveagents.ingress.common.TaskValidationException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST ingress for tasks (webmvc). {@code X-API-Key} carries AUTH ONLY (see
 * {@link ApiKeyAuthFilter}); identity and topology come exclusively from the envelope body
 * (docs/ENVELOPE.md).
 *
 * <ul>
 *   <li>{@code POST /tasks} — 202 Accepted for a new task, 200 OK for a duplicate (idempotent
 *       by {@code task_id}); body {@code {"task_id","status"}}. Validation failures and
 *       unbindable bodies (bad UUID, unknown {@code forced_topology} enum value) map to 400
 *       problem-details.</li>
 *   <li>{@code GET /tasks/{id}} — current state; 404 problem-details when unknown. Fields
 *       {@code result}/{@code error} are the raw JSON strings persisted on the record and are
 *       {@code null} when absent (always present as keys — documented choice).</li>
 *   <li>{@code GET /tasks/{id}/events} — SSE progress stream with {@code Last-Event-ID}
 *       replay (see {@link TaskEventStreamer}); unknown task 404s before the stream opens.</li>
 * </ul>
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskIngressService ingressService;
    private final TaskStore taskStore;
    private final TaskEventStreamer eventStreamer;

    public TaskController(TaskIngressService ingressService, TaskStore taskStore, TaskEventStreamer eventStreamer) {
        this.ingressService = ingressService;
        this.taskStore = taskStore;
        this.eventStreamer = eventStreamer;
    }

    /** POST response body: {@code {"task_id": "...", "status": "..."}}. */
    public record TaskSubmitted(
            @JsonProperty("task_id") UUID taskId,
            @JsonProperty("status") String status) {}

    /** GET response body; {@code result}/{@code error} are raw JSON strings, {@code null} when absent. */
    public record TaskState(
            @JsonProperty("task_id") UUID taskId,
            @JsonProperty("status") String status,
            @JsonProperty("topology") String topology,
            @JsonProperty("result") String result,
            @JsonProperty("error") String error) {}

    @PostMapping
    public ResponseEntity<TaskSubmitted> submit(@RequestBody TaskEnvelope envelope) {
        validate(envelope);
        TaskIngressService.Submission submission = ingressService.submit(envelope);
        TaskRecord record = submission.record();
        return ResponseEntity
                .status(submission.created() ? 202 : 200)
                .body(new TaskSubmitted(record.taskId(), record.status().name()));
    }

    @GetMapping("/{id}")
    public TaskState state(@PathVariable("id") UUID id) {
        TaskRecord record = taskStore.find(id).orElseThrow(() -> new TaskNotFoundException(id));
        return new TaskState(
                record.taskId(),
                record.status().name(),
                record.topology() == null ? null : record.topology().name(),
                record.result(),
                record.error());
    }

    @GetMapping("/{id}/events")
    public SseEmitter events(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        taskStore.find(id).orElseThrow(() -> new TaskNotFoundException(id));
        return eventStreamer.stream(id, parseLastEventId(lastEventId));
    }

    /**
     * Semantic validation delegates to the shared {@link EnvelopeValidator} — the single
     * truth reused by the MCP tool adapter so envelope semantics cannot drift between
     * protocols (T24 parity depends on this).
     */
    private static void validate(TaskEnvelope envelope) {
        EnvelopeValidator.validate(envelope);
    }

    /** Absent/blank header replays the full history; a non-numeric value is a client error. */
    private static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            throw new TaskValidationException("Last-Event-ID header must be a numeric event sequence id");
        }
    }
}
