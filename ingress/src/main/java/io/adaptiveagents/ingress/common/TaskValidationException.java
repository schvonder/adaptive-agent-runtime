package io.adaptiveagents.ingress.common;

/**
 * An envelope failed the v1 semantic validation rules that Jackson cannot express (task_type
 * allow-list, breadth range, positive deadline/budget, non-blank prompt). Protocol adapters
 * translate it their own way: REST maps it to a 400 RFC 7807 problem-detail
 * ({@code ApiExceptionHandler}); the MCP server surfaces it as a tool error result; the Kafka
 * listener replies INVALID and parks the poison envelope on the DLT.
 */
public class TaskValidationException extends RuntimeException {

    public TaskValidationException(String detail) {
        super(detail);
    }
}
