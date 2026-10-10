package io.adaptiveagents.ingress.rest;

import io.adaptiveagents.core.progress.ProgressEvent;
import io.adaptiveagents.core.progress.ProgressService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE live tail over the {@link ProgressService} event log for {@code GET /tasks/{id}/events}.
 *
 * <p>Stream shape: the first scheduler tick replays {@code sinceSeq(taskId, since)} — the value
 * of the request's {@code Last-Event-ID} header when present, 0 (full history) otherwise — and
 * every tick after that tails new rows with a fixed delay of {@value #POLL_DELAY_MS} ms. Each
 * event goes out as one SSE frame: {@code id: <seq>}, {@code event: <type>},
 * {@code data: <payload json>}. The first event whose type is terminal
 * (TASK_COMPLETED/TASK_FAILED/TASK_DEADLINE_EXCEEDED/TASK_BUDGET_EXCEEDED) is sent and then the
 * emitter completes — the stream always ends on the task's terminal event.
 *
 * <p>Lifecycle: the emitter is created with timeout {@code 0L} (no container-side timeout); the
 * stream ends either on the terminal event or on client disconnect, and {@code onCompletion}/
 * {@code onTimeout}/{@code onError} all cancel the polling future, so no emitter ever leaks a
 * scheduler slot. One shared daemon {@link ScheduledExecutorService} (2 threads) serves all
 * emitters — polling never runs on a caller (request) thread. Early {@code send}/{@code
 * complete} calls issued before the emitter is handed to the MVC infrastructure are buffered by
 * {@link SseEmitter} itself, so the zero-delay first tick is safe.
 */
@Component
public class TaskEventStreamer {

    private static final Logger log = LoggerFactory.getLogger(TaskEventStreamer.class);

    /** Terminal progress event types: the last frame of every stream. */
    static final Set<String> TERMINAL_TYPES = Set.of(
            "TASK_COMPLETED", "TASK_FAILED", "TASK_DEADLINE_EXCEEDED", "TASK_BUDGET_EXCEEDED");

    private static final long POLL_DELAY_MS = 150;

    private final ProgressService progressService;
    private final ScheduledExecutorService scheduler;

    public TaskEventStreamer(ProgressService progressService) {
        this.progressService = progressService;
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "sse-progress-tail");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens the emitter for {@code taskId}, replaying from {@code sinceSeq} (exclusive) and then
     * tailing live until the task's terminal event.
     */
    public SseEmitter stream(UUID taskId, long sinceSeq) {
        // 0L = no container timeout: completion is driven by the terminal event or client
        // disconnect; cleanup callbacks cover both paths.
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong lastSent = new AtomicLong(sinceSeq);
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> tail = scheduler.scheduleWithFixedDelay(
                () -> pollTick(emitter, taskId, lastSent, finished),
                0, POLL_DELAY_MS, TimeUnit.MILLISECONDS);
        Runnable cleanup = () -> tail.cancel(false);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    /**
     * One polling tick. Never rethrows: {@code scheduleWithFixedDelay} suppresses all future
     * runs after an uncaught throw, and this loop must keep polling until the stream ends.
     */
    private void pollTick(SseEmitter emitter, UUID taskId, AtomicLong lastSent, AtomicBoolean finished) {
        if (finished.get()) {
            return;
        }
        try {
            for (ProgressEvent event : progressService.sinceSeq(taskId, lastSent.get())) {
                send(emitter, event);
                lastSent.set(event.seq());
                if (TERMINAL_TYPES.contains(event.type())) {
                    if (finished.compareAndSet(false, true)) {
                        emitter.complete();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            if (finished.compareAndSet(false, true)) {
                log.warn("SSE stream for task {} aborted: {}", taskId, e.getMessage());
                emitter.completeWithError(e);
            }
        }
    }

    private void send(SseEmitter emitter, ProgressEvent event) throws IOException {
        String payload = event.payload() == null ? "" : event.payload();
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.seq()))
                .name(event.type())
                .data(payload, MediaType.APPLICATION_JSON));
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
