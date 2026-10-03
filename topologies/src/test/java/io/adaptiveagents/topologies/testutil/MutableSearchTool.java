package io.adaptiveagents.topologies.testutil;

import io.adaptiveagents.workload.tools.SearchResult;
import io.adaptiveagents.workload.tools.SearchTool;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SearchTool} test double with three modes: canned results (default), empty results, or
 * throw. Controlled per test and reset between tests.
 */
public class MutableSearchTool implements SearchTool {

    private final AtomicBoolean throwing = new AtomicBoolean(false);
    private final AtomicBoolean empty = new AtomicBoolean(false);

    public void setThrowing(boolean value) {
        throwing.set(value);
    }

    public void setReturnEmpty(boolean value) {
        empty.set(value);
    }

    public void reset() {
        throwing.set(false);
        empty.set(false);
    }

    @Override
    public List<SearchResult> search(String query) {
        if (throwing.get()) {
            throw new RuntimeException("search backend unavailable");
        }
        if (empty.get()) {
            return List.of();
        }
        return List.of(new SearchResult("Canned Result", "https://example.com/result", "A canned snippet."));
    }
}
