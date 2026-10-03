package io.adaptiveagents.workload.tools;

/**
 * A numbered citation.
 *
 * <p>Implements {@link java.io.Serializable} so langgraph4j's default state serializer can clone
 * graph state that carries citations between nodes.</p>
 *
 * @param index 1-based, stable ordering position of the citation
 * @param title title of the cited source
 * @param url   URL of the cited source
 */
public record Citation(int index, String title, String url) implements java.io.Serializable {
}
