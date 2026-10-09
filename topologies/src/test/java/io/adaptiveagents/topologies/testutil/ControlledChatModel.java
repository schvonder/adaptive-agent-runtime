package io.adaptiveagents.topologies.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link ChatModel} test double with three controllable behaviours, reset between tests:
 * <ul>
 *   <li>a scripted response queue (last entry repeats);</li>
 *   <li>an optional per-call sleep (to hold a run open and let a deadline pass);</li>
 *   <li>an optional one-shot block on a specific call index (to simulate a mid-run crash for
 *       checkpoint/resume tests).</li>
 * </ul>
 *
 * <p>The block waits on a latch <em>outside</em> the synchronized script drain, so an abandoned
 * (blocked) call never holds the monitor and a subsequent call can drain the script.</p>
 */
public class ControlledChatModel implements ChatModel {

    private final List<Object> script = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger(0);
    private int cursor = 0;
    private volatile long delayMs = 0;
    private volatile int blockOnCall = -1;
    private volatile CountDownLatch latch;

    public void enqueue(ChatResponse... responses) {
        script.addAll(List.of(responses));
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    /** Block (await {@code latch}) the {@code callNumber}-th call (1-based), exactly once. */
    public void blockCall(int callNumber, CountDownLatch latch) {
        this.blockOnCall = callNumber;
        this.latch = latch;
    }

    public void reset() {
        script.clear();
        cursor = 0;
        callCount.set(0);
        delayMs = 0;
        blockOnCall = -1;
        latch = null;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        int call = callCount.incrementAndGet();
        if (call == blockOnCall) {
            CountDownLatch toAwait = latch;
            if (toAwait != null) {
                try {
                    toAwait.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (script.isEmpty()) {
                throw new IllegalStateException("ControlledChatModel has no scripted responses");
            }
            Object entry = script.get(Math.min(cursor, script.size() - 1));
            cursor++;
            if (entry instanceof RuntimeException error) {
                throw error;
            }
            return (ChatResponse) entry;
        }
    }

    public static ChatResponse text(String text) {
        return ScriptedChatModel.text(text);
    }

    public static ChatResponse textWithUsage(String text, String model, int promptTokens, int completionTokens) {
        return ScriptedChatModel.textWithUsage(text, model, promptTokens, completionTokens);
    }
}
