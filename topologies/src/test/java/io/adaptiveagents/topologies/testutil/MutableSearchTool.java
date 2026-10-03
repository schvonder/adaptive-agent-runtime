package io.adaptiveagents.topologies.testutil;

import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SearchTool} test double with four modes: canned results (default), empty results, throw on
 * every call, or throw only when the query contains a specific substring (per-branch fault
 * injection). Controlled per test and reset between tests.
 */
public class MutableSearchTool implements SearchTool {

    private final AtomicBoolean throwing = new AtomicBoolean(false);
    private final AtomicBoolean empty = new AtomicBoolean(false);
    private final AtomicReference<String> throwingOnQueryContaining = new AtomicReference<>(null);

    public void setThrowing(boolean value) {
        throwing.set(value);
    }

    public void setReturnEmpty(boolean value) {
        empty.set(value);
    }

    /** Throw only when the query contains {@code substring}; {@code null} disables. */
    public void setThrowingOnQueryContaining(String substring) {
        throwingOnQueryContaining.set(substring);
    }

    public void reset() {
        throwing.set(false);
        empty.set(false);
        throwingOnQueryContaining.set(null);
    }

    @Override
    public List<SearchResult> search(String query) {
        if (throwing.get()) {
            throw new RuntimeException("search backend unavailable");
        }
        String faultSubstring = throwingOnQueryContaining.get();
        if (faultSubstring != null && query.contains(faultSubstring)) {
            throw new RuntimeException("search backend unavailable for query containing: " + faultSubstring);
        }
        if (empty.get()) {
            return List.of();
        }
        return List.of(new SearchResult("Canned Result", "https://example.com/result", "A canned snippet."));
    }
}
