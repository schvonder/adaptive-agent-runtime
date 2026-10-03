package io.adaptiveagents.core.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TaskStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    public TaskStore(JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    public TaskRecord submitOrGet(TaskEnvelope envelope) {
        try {
            String envelopeJson = mapper.writeValueAsString(envelope);
            jdbcTemplate.update(
                    "INSERT INTO tasks (task_id, envelope, status, budget_usd, deadline_ms) VALUES (?, ?::jsonb, ?, ?, ?)",
                    envelope.taskId(),
                    envelopeJson,
                    TaskStatus.SUBMITTED.name(),
                    envelope.budgetUsd(),
                    envelope.deadlineMs()
            );
        } catch (DuplicateKeyException e) {
            // Already exists, ignore and fetch below to resolve idempotently
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key value")) {
                // Raw fallback in case DuplicateKeyException isn't thrown natively by the driver wrapper
            } else {
                throw new RuntimeException(e);
            }
        }
        return find(envelope.taskId()).orElseThrow(() -> new IllegalStateException("Task should exist"));
    }

    public Optional<TaskRecord> find(UUID taskId) {
        List<TaskRecord> results = jdbcTemplate.query("SELECT * FROM tasks WHERE task_id = ?", (rs, rowNum) -> {
            try {
                return new TaskRecord(
                        UUID.fromString(rs.getString("task_id")),
                        mapper.readValue(rs.getString("envelope"), TaskEnvelope.class),
                        TaskStatus.valueOf(rs.getString("status")),
                        rs.getString("topology") != null ? Topology.valueOf(rs.getString("topology")) : null,
                        rs.getString("decision"),
                        rs.getString("result"),
                        rs.getString("error"),
                        rs.getBigDecimal("budget_usd"),
                        rs.getObject("deadline_ms", Long.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getLong("version")
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, taskId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public TaskRecord transition(UUID taskId, TaskStatus target) {
        TaskRecord current = find(taskId).orElseThrow(() -> new IllegalTaskStateException("Task not found"));
        if (!current.status().canTransitionTo(target)) {
            throw new IllegalTaskStateException("Cannot transition from " + current.status() + " to " + target);
        }
        
        int updated = jdbcTemplate.update(
                "UPDATE tasks SET status = ?, version = version + 1, updated_at = now() WHERE task_id = ? AND version = ?",
                target.name(), taskId, current.version()
        );
        
        if (updated == 0) {
            throw new IllegalTaskStateException("Optimistic concurrency failure or illegal state for task " + taskId + " (status: " + current.status() + ")");
        }
        
        return find(taskId).get();
    }

    public void completeWithResult(UUID taskId, String resultJson) {
        TaskRecord current = find(taskId).orElseThrow(() -> new IllegalTaskStateException("Task not found: " + taskId));
        if (!current.status().canTransitionTo(TaskStatus.SUCCEEDED)) {
            throw new IllegalTaskStateException("Cannot transition from " + current.status() + " to " + TaskStatus.SUCCEEDED);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tasks SET status = ?, result = ?::jsonb, version = version + 1, updated_at = now() WHERE task_id = ? AND version = ?",
                TaskStatus.SUCCEEDED.name(), resultJson, taskId, current.version()
        );
        if (updated == 0) {
            throw new IllegalTaskStateException("Optimistic concurrency failure or illegal state for task " + taskId + " (status: " + current.status() + ")");
        }
    }

    public void completeWithError(UUID taskId, String errorJson) {
        TaskRecord current = find(taskId).orElseThrow(() -> new IllegalTaskStateException("Task not found: " + taskId));
        if (!current.status().canTransitionTo(TaskStatus.FAILED)) {
            throw new IllegalTaskStateException("Cannot transition from " + current.status() + " to " + TaskStatus.FAILED);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tasks SET status = ?, error = ?::jsonb, version = version + 1, updated_at = now() WHERE task_id = ? AND version = ?",
                TaskStatus.FAILED.name(), errorJson, taskId, current.version()
        );
        if (updated == 0) {
            throw new IllegalTaskStateException("Optimistic concurrency failure or illegal state for task " + taskId + " (status: " + current.status() + ")");
        }
    }

    public void recordDecision(UUID taskId, Topology topology, String decisionJson) {
        int updated = jdbcTemplate.update(
                "UPDATE tasks SET topology = ?, decision = ?::jsonb, updated_at = now() WHERE task_id = ?",
                topology.name(), decisionJson, taskId
        );
        if (updated == 0) {
            throw new IllegalTaskStateException("Task not found: " + taskId);
        }
    }
}
