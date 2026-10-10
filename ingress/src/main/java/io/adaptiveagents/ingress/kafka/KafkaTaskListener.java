package io.adaptiveagents.ingress.kafka;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.ingress.rest.TaskIngressService;
import io.adaptiveagents.core.store.TaskRecord;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.SendTo;

/**
 * Request/reply Kafka ingress: consumes {@code TaskEnvelope} JSON from
 * {@code adaptive-tasks-request} and replies on {@code adaptive-tasks-reply} with an
 * IMMEDIATE post-submission ACK {@code {"task_id","status","decision"}} — the reply is sent
 * right after {@link TaskIngressService#submit} returns and the listener thread NEVER blocks
 * on task completion (fire-and-forget execution is the service's own contract). The final
 * result is observed via {@code get_task} REST polling or the progress-topic tail
 * (bench/README.md).
 *
 * <p>Identity is taken EXCLUSIVELY from the parsed body (docs/ENVELOPE.md): the optional
 * {@code X-Task-Id} record header is logged as tracing correlation and never read for
 * identity — assert-by-construction below, {@code taskId} only ever comes from
 * {@code envelope.taskId()}.
 *
 * <p>Poison envelopes (malformed JSON, unbindable enum/UUID, failed semantic validation)
 * yield the error reply {@code {"task_id":null,"status":"INVALID","error":"..."}} AND a copy
 * of the offending raw record on {@code adaptive-tasks-request-dlt}; the listener survives
 * and the next valid record is processed normally. Infrastructure failures during submission
 * produce the same error-reply shape (without the DLT copy — the record itself was valid).
 */
public class KafkaTaskListener {

    static final String REQUEST_TOPIC = "adaptive-tasks-request";
    static final String REPLY_TOPIC = "adaptive-tasks-reply";
    static final String DLT_TOPIC = "adaptive-tasks-request-dlt";
    static final String TRACE_HEADER = "X-Task-Id";

    private static final Logger log = LoggerFactory.getLogger(KafkaTaskListener.class);

    private final TaskIngressService taskIngressService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaTaskListener(
            TaskIngressService taskIngressService,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.taskIngressService = taskIngressService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = REQUEST_TOPIC, groupId = "adaptive-runtime")
    @SendTo(REPLY_TOPIC)
    public String onRequest(ConsumerRecord<String, String> record) {
        String traceId = headerValue(record, TRACE_HEADER);
        if (traceId != null) {
            log.info("Kafka request received (trace header X-Task-Id={}, tracing correlation only)", traceId);
        }
        TaskEnvelope envelope;
        try {
            envelope = objectMapper.readValue(record.value(), TaskEnvelope.class);
            validate(envelope);
        } catch (JacksonException | ValidationException e) {
            return poison(record, e.getMessage());
        }
        try {
            TaskIngressService.Submission submission = taskIngressService.submit(envelope);
            return ack(submission.record());
        } catch (RuntimeException e) {
            log.error("Task submission failed for envelope task_id={}; replying error status",
                    envelope.taskId(), e);
            return errorReply(envelope.taskId(), "submission failed: " + e.getMessage());
        }
    }

    /** Immediate ACK: current persisted status plus the routing decision, if any. */
    private String ack(TaskRecord record) {
        try {
            String decisionJson = record.decision();
            JsonNode decision = decisionJson == null ? null : objectMapper.readTree(decisionJson);
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("task_id", record.taskId().toString())
                    .put("status", record.status().name())
                    .set("decision", decision == null ? objectMapper.nullNode() : decision));
        } catch (JacksonException e) {
            // The record fields come from the DB; a serialization failure here is a bug guard,
            // not an expected path — fall back to the minimal ack shape.
            log.error("Failed to serialize ACK for task {}", record.taskId(), e);
            return errorReply(record.taskId(), "ack serialization failed");
        }
    }

    /** Poison path: INVALID error reply plus the raw record copied to the DLT for inspection. */
    private String poison(ConsumerRecord<String, String> record, String reason) {
        log.warn("Poison envelope on {}: {} (raw payload copied to {})",
                REQUEST_TOPIC, reason, DLT_TOPIC);
        ProducerRecord<Object, Object> dltRecord =
                new ProducerRecord<>(DLT_TOPIC, record.key(), record.value());
        record.headers().forEach(header -> dltRecord.headers().add(header));
        kafkaTemplate.send(dltRecord).whenComplete((ignored, sendError) -> {
            if (sendError != null) {
                log.warn("Failed to deliver poison envelope to {}: {}",
                        DLT_TOPIC, sendError.getMessage());
            }
        });
        return errorReply(null, reason);
    }

    private String errorReply(UUID taskId, String reason) {
        try {
            var node = objectMapper.createObjectNode();
            if (taskId == null) {
                node.putNull("task_id");
            } else {
                node.put("task_id", taskId.toString());
            }
            node.put("status", "INVALID");
            node.put("error", Objects.requireNonNullElse(reason, "unknown error"));
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            return "{\"task_id\":null,\"status\":\"INVALID\",\"error\":\"ack serialization failed\"}";
        }
    }

    private static String headerValue(ConsumerRecord<String, String> record, String header) {
        var value = record.headers().lastHeader(header);
        return value == null ? null : new String(value.value(), StandardCharsets.UTF_8);
    }

    /** Semantic failures carried as a control-flow signal into the poison path. */
    private static final class ValidationException extends RuntimeException {
        ValidationException(String detail) {
            super(detail);
        }
    }

    /** Mirrors the REST ingress's manual envelope validation (TaskController rules). */
    private static void validate(TaskEnvelope envelope) {
        if (envelope.taskId() == null) {
            throw new ValidationException("task_id is required");
        }
        if (!"research".equals(envelope.taskType())) {
            throw new ValidationException("task_type must be 'research' (only v1 type supported)");
        }
        if (envelope.prompt() == null || envelope.prompt().isBlank()) {
            throw new ValidationException("prompt must not be blank");
        }
        if (envelope.breadthHint() < 1 || envelope.breadthHint() > 5) {
            throw new ValidationException("breadth_hint must be between 1 and 5");
        }
        if (envelope.deadlineMs() != null && envelope.deadlineMs() <= 0) {
            throw new ValidationException("deadline_ms must be greater than 0");
        }
        if (envelope.budgetUsd() != null && envelope.budgetUsd().signum() <= 0) {
            throw new ValidationException("budget_usd must be greater than 0");
        }
    }
}
