package io.adaptiveagents.core.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ProgressService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;
    private final ObjectProvider<Tracer> tracerProvider;

    @Autowired
    public ProgressService(JdbcTemplate jdbcTemplate, ObjectMapper mapper, ObjectProvider<Tracer> tracerProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.tracerProvider = tracerProvider;
    }

    public ProgressService(JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        this(jdbcTemplate, mapper, null);
    }

    public long publish(UUID taskId, String type, ObjectNode payload) {
        ObjectNode enrichedPayload = payload;
        Tracer tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        if (tracer != null) {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null && currentSpan.context() != null) {
                String traceId = currentSpan.context().traceId();
                if (traceId != null && !traceId.isBlank()) {
                    if (enrichedPayload == null) {
                        enrichedPayload = mapper.createObjectNode();
                    }
                    if (!enrichedPayload.has("traceId")) {
                        enrichedPayload.put("traceId", traceId);
                    }
                }
            }
        }

        String payloadJson = null;
        if (enrichedPayload != null) {
            try {
                payloadJson = mapper.writeValueAsString(enrichedPayload);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize payload", e);
            }
        }
        
        Long seq = jdbcTemplate.queryForObject(
                "INSERT INTO progress_events (task_id, type, payload) VALUES (?, ?, ?::jsonb) RETURNING seq",
                Long.class,
                taskId, type, payloadJson
        );
        if (seq == null) {
            throw new IllegalStateException("Failed to retrieve seq from RETURNING clause");
        }
        return seq;
    }

    public List<ProgressEvent> sinceSeq(UUID taskId, long sinceSeq) {
        return jdbcTemplate.query(
                "SELECT seq, task_id, type, payload, created_at FROM progress_events WHERE task_id = ? AND seq > ? ORDER BY seq ASC",
                (rs, rowNum) -> new ProgressEvent(
                        rs.getLong("seq"),
                        UUID.fromString(rs.getString("task_id")),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                taskId, sinceSeq
        );
    }

    /**
     * Highest assigned global event sequence, {@code 0} when no event exists yet. Used by
     * live-tail publishers (e.g. the Kafka progress topic) to anchor their cursor at startup
     * so they relay only events that appear from app start onward.
     */
    public long latestSeq() {
        Long latest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM progress_events", Long.class);
        return latest == null ? 0L : latest;
    }

    /**
     * All events strictly after {@code sinceSeq} across ALL tasks, in global {@code seq}
     * ascending order (per-task ordering is implied: seq is monotonically assigned per insert).
     * Live-tail counterpart of the per-task {@link #sinceSeq(UUID, long)}.
     */
    public List<ProgressEvent> sinceSeqGlobally(long sinceSeq) {
        return jdbcTemplate.query(
                "SELECT seq, task_id, type, payload, created_at FROM progress_events WHERE seq > ? ORDER BY seq ASC",
                (rs, rowNum) -> new ProgressEvent(
                        rs.getLong("seq"),
                        UUID.fromString(rs.getString("task_id")),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                sinceSeq
        );
    }
}
