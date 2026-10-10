package io.adaptiveagents.core.trace;

import java.util.UUID;

/**
 * Thread-local context tracking the current task id for observability and tracing correlation.
 */
public final class TaskTraceContext {

    private static final ThreadLocal<String> CURRENT_TASK_ID = new ThreadLocal<>();

    private TaskTraceContext() {}

    public static void setTaskId(String taskId) {
        if (taskId == null) {
            CURRENT_TASK_ID.remove();
        } else {
            CURRENT_TASK_ID.set(taskId);
        }
    }

    public static void setTaskId(UUID taskId) {
        setTaskId(taskId == null ? null : taskId.toString());
    }

    public static String getTaskId() {
        return CURRENT_TASK_ID.get();
    }

    public static void clear() {
        CURRENT_TASK_ID.remove();
    }
}
