package io.adaptiveagents.core.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.TaskStatus;
import io.adaptiveagents.core.model.Topology;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class TaskStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public TaskStore(JdbcTemplate jdbcTemplate, ObjectMapper mapper, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Idempotent task submission, guarded by a transaction-scoped advisory lock on the task id.
     *
     * <p>Runs {@code pg_advisory_xact_lock(hashtext(task_id))}, the existence check and the insert
     * inside ONE transaction, so concurrent submissions of the same task id (including at-least-once
     * redelivery) serialize: exactly one caller inserts and observes {@code created == true}; every
     * other caller takes the lock after that commit, sees the existing row, and observes
     * {@code created == false} without any exception. The tasks primary key remains the final
     * uniqueness guarantee; the {@link DuplicateKeyException} catch is a defensive fallback for
     * callers that somehow bypass the lock.
     *
     * <p>Safe to call standalone (opens its own transaction) or from inside
     * {@link #withTaskLock(UUID, Supplier)} — the advisory lock is re-entrant within a transaction.
     */
    public SubmitResult submitOrGet(TaskEnvelope envelope) {
        String envelopeJson = serialize(envelope);
        return transactionTemplate.execute(tx -> {
            acquireTaskLock(envelope.taskId());
            Optional<TaskRecord> existing = find(envelope.taskId());
            if (existing.isPresent()) {
                return new SubmitResult(existing.get(), false);
            }
            try {
                jdbcTemplate.update(
                        "INSERT INTO tasks (task_id, envelope, status, budget_usd, deadline_ms) VALUES (?, ?::jsonb, ?, ?, ?)",
                        envelope.taskId(),
                        envelopeJson,
                        TaskStatus.SUBMITTED.name(),
                        envelope.budgetUsd(),
                        envelope.deadlineMs()
                );
            } catch (DuplicateKeyException e) {
                // Unreachable under the advisory lock; kept so any caller bypassing the lock still
                // gets 200-with-existing semantics instead of an error.
                return new SubmitResult(find(envelope.taskId()).orElseThrow(() -> e), false);
            }
            return new SubmitResult(find(envelope.taskId()).orElseThrow(), true);
        });
    }

    /**
     * Runs {@code body} inside a transaction holding the transaction-scoped advisory lock for
     * {@code taskId} ({@code pg_advisory_xact_lock(hashtext(task_id))}), serializing all
     * lock-scoped work for that task — submission, status check, routing transitions — across
     * threads and processes. A duplicate submission blocks until the lock holder commits, then
     * observes the committed state.
     */
    public <T> T withTaskLock(UUID taskId, Supplier<T> body) {
        return transactionTemplate.execute(tx -> {
            acquireTaskLock(taskId);
            return body.get();
        });
    }

    private void acquireTaskLock(UUID taskId) {
        // pg_advisory_xact_lock signals failure only by throwing; its void result is discarded.
        // The ResultSetExtractor form consumes the single produced row, which forces evaluation of
        // the volatile function (and therefore the lock acquisition) on the tx-bound connection.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {
            rs.next();
            return null;
        }, taskId.toString());
    }

    private String serialize(TaskEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<TaskRecord> find(UUID taskId) {
        List<TaskRecord> results = jdbcTemplate.query("SELECT * FROM tasks WHERE task_id = ?", this::mapRow, taskId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * All tasks in a given status, in deterministic order (creation time, then task id as a
     * tie-break). Startup rescan uses this to decide, in a stable sequence, which RUNNING tasks to
     * resume and which to fail over to a terminal status.
     */
    public List<TaskRecord> findByStatus(TaskStatus status) {
        return jdbcTemplate.query(
                "SELECT * FROM tasks WHERE status = ? ORDER BY created_at ASC, task_id ASC",
                this::mapRow, status.name());
    }

    private TaskRecord mapRow(ResultSet rs, int rowNum) {
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

    /**
     * Moves a RUNNING task to a terminal status, writing the payload to the {@code result} column
     * for {@link TaskStatus#SUCCEEDED} and to the {@code error} column for every other terminal
     * status ({@link TaskStatus#FAILED}, {@link TaskStatus#DEADLINE_EXCEEDED},
     * {@link TaskStatus#BUDGET_EXCEEDED}).
     *
     * @throws IllegalTaskStateException when the task is missing, the current status cannot reach
     *     {@code target}, or an optimistic-concurrency collision occurred
     */
    public void completeTerminal(UUID taskId, TaskStatus target, String payloadJson) {
        if (!target.isTerminal()) {
            throw new IllegalArgumentException("Target status must be terminal: " + target);
        }
        String column = switch (target) {
            case SUCCEEDED -> "result";
            case FAILED, DEADLINE_EXCEEDED, BUDGET_EXCEEDED -> "error";
            default -> throw new IllegalArgumentException("Unsupported terminal status: " + target);
        };
        TaskRecord current = find(taskId).orElseThrow(() -> new IllegalTaskStateException("Task not found: " + taskId));
        if (!current.status().canTransitionTo(target)) {
            throw new IllegalTaskStateException("Cannot transition from " + current.status() + " to " + target);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tasks SET status = ?, " + column + " = ?::jsonb, version = version + 1, updated_at = now() WHERE task_id = ? AND version = ?",
                target.name(), payloadJson, taskId, current.version()
        );
        if (updated == 0) {
            throw new IllegalTaskStateException("Optimistic concurrency failure or illegal state for task " + taskId + " (status: " + current.status() + ")");
        }
    }

    public void completeWithResult(UUID taskId, String resultJson) {
        completeTerminal(taskId, TaskStatus.SUCCEEDED, resultJson);
    }

    public void completeWithError(UUID taskId, String errorJson) {
        completeTerminal(taskId, TaskStatus.FAILED, errorJson);
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
