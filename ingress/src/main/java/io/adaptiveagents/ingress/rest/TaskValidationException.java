package io.adaptiveagents.ingress.rest;

/**
 * 400 (RFC 7807, mapped by {@link ApiExceptionHandler}): the request body failed task-envelope
 * validation rules that Jackson cannot express (task_type allow-list, breadth range, positive
 * deadline/budget, non-blank prompt).
 */
public class TaskValidationException extends RuntimeException {

    public TaskValidationException(String detail) {
        super(detail);
    }
}
