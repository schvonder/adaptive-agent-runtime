package io.adaptiveagents.workload.tools;

/**
 * A single web search result as returned by a {@link SearchTool}.
 *
 * @param title   human readable title of the result
 * @param url     canonical URL of the result
 * @param snippet short textual excerpt shown alongside the result
 */
public record SearchResult(String title, String url, String snippet) {
}
