package io.adaptiveagents.workload.tools;

/**
 * Page fetch abstraction. Implementations must never throw for a missing page;
 * they return an empty string instead.
 */
public interface FetchTool {

    /**
     * Fetch the textual content of a page.
     *
     * @param url page URL
     * @return page content, never {@code null}; empty when the page is unavailable
     */
    String fetch(String url);
}
