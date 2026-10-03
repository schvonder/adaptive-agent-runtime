package io.adaptiveagents.workload.tools;

import java.util.List;

/**
 * Web search abstraction. Implementations must never throw for a missing query;
 * they return an empty list instead.
 */
public interface SearchTool {

    /**
     * Search the web for the given query.
     *
     * @param query free-text query
     * @return matching results, never {@code null}; empty when nothing is found
     */
    List<SearchResult> search(String query);
}
