package io.adaptiveagents.ingress.kafka;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.progress.ProgressEvent;
import io.adaptiveagents.core.progress.ProgressService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Outbox tail relaying {@code progress_events} rows to the {@code adaptive-tasks-progress}
 * Kafka topic. LIVE-TAIL CONTRACT: the topic carries only events that appear from app start
 * onward — on the first tick the in-memory cursor is anchored to
 * {@link ProgressService#latestSeq()} (the current max seq); historical replay belongs to the
 * REST SSE endpoint with {@code Last-Event-ID} (docs/ENVELOPE.md). The anchor is taken lazily
 * on the first tick, not at bean construction, so it is guaranteed to run after schema
 * migration during context startup.
 *
 * <p>Semantics: {@code @Scheduled(fixedDelay)} is serial by default (one tick at a time, next
 * fire only after the previous completes). Each tick selects events with {@code seq > cursor}
 * in global seq order and publishes each record with key {@code task_id} — per-task ordering
 * is preserved because a task's events share the key (single partition) and the producer is
 * idempotent. The tick NEVER throws: any failure is logged at warn and retried on the next
 * tick (fixed-delay suppression rule).
 */
public class ProgressTopicPublisher {

    static final String PROGRESS_TOPIC = "adaptive-tasks-progress";

    private static final Logger log = LoggerFactory.getLogger(ProgressTopicPublisher.class);
    private static final long CURSOR_UNANCHORED = -1;

    private final ProgressService progressService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private volatile long lastSeq = CURSOR_UNANCHORED;

    public ProgressTopicPublisher(
            ProgressService progressService,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.progressService = progressService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${runtime.kafka.progress-poll-ms:250}")
    public void publishNewEvents() {
        try {
            if (lastSeq == CURSOR_UNANCHORED) {
                lastSeq = progressService.latestSeq();
                log.info("Progress topic tail anchored at seq {}; publishing events from app start onward",
                        lastSeq);
                return;
            }
            for (ProgressEvent event : progressService.sinceSeqGlobally(lastSeq)) {
                kafkaTemplate.send(progressRecord(event)).whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.warn("Failed to publish progress event seq={} to {}: {}",
                                event.seq(), PROGRESS_TOPIC, error.getMessage());
                    }
                });
                lastSeq = event.seq();
            }
        } catch (Exception e) {
            log.warn("Progress topic tick failed; retrying next tick: {}", e.getMessage());
        }
    }

    /** Topic frame: {@code {"seq":…,"task_id":"…","type":"…","payload":<payload json>}}. */
    private String frame(ProgressEvent event) throws JacksonException {
        JsonNode payload = event.payload() == null
                ? objectMapper.nullNode()
                : objectMapper.readTree(event.payload());
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("seq", event.seq())
                .put("task_id", event.taskId().toString())
                .put("type", event.type())
                .set("payload", payload));
    }

    private ProducerRecord<Object, Object> progressRecord(ProgressEvent event) throws JacksonException {
        return new ProducerRecord<>(PROGRESS_TOPIC, event.taskId().toString(), frame(event));
    }
}
