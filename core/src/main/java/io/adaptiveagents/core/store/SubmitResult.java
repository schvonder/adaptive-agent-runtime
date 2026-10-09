package io.adaptiveagents.core.store;

/**
 * Result of an idempotent task submission: the persisted record plus whether this call created it.
 *
 * <p>{@code created == false} means a task with the same id already existed (duplicate submission
 * or at-least-once redelivery); the caller must resolve to the existing state instead of routing
 * or executing again.
 */
public record SubmitResult(TaskRecord record, boolean created) {}
