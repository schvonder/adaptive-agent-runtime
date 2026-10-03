package io.adaptiveagents.workload.tools;

/**
 * A numbered citation.
 *
 * @param index 1-based, stable ordering position of the citation
 * @param title title of the cited source
 * @param url   URL of the cited source
 */
public record Citation(int index, String title, String url) {
}
