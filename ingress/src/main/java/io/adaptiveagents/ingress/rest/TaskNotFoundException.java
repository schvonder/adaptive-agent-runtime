package io.adaptiveagents.ingress.rest;

import java.util.UUID;

/**
 * 404 (RFC 7807, mapped by {@link ApiExceptionHandler}): no task exists for the referenced id.
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(UUID taskId) {
        super("No task with id " + taskId);
    }
}
