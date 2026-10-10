package io.adaptiveagents.workload.tools;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring AI tool surface exposed to agents. Each method delegates to a workload tool bean.
 */
@Component
public class ResearchTools {

    private final SearchTool searchTool;
    private final FetchTool fetchTool;
    private final CiteTool citeTool;
    private final ObjectProvider<Tracer> tracerProvider;

    @Autowired
    public ResearchTools(
            SearchTool searchTool,
            FetchTool fetchTool,
            CiteTool citeTool,
            ObjectProvider<Tracer> tracerProvider) {
        this.searchTool = searchTool;
        this.fetchTool = fetchTool;
        this.citeTool = citeTool;
        this.tracerProvider = tracerProvider;
    }

    public ResearchTools(SearchTool searchTool, FetchTool fetchTool, CiteTool citeTool) {
        this(searchTool, fetchTool, citeTool, null);
    }

    @Tool(name = "web_search",
            description = "Search the web for a query and return ranked results (title, url, snippet).")
    public List<SearchResult> web_search(String query) {
        return traceToolCall("web_search", () -> searchTool.search(query));
    }

    @Tool(name = "fetch_page",
            description = "Fetch the textual content of a web page by URL.")
    public String fetch_page(String url) {
        return traceToolCall("fetch_page", () -> fetchTool.fetch(url));
    }

    @Tool(name = "cite_sources",
            description = "Build a numbered citation list from search results and the URLs that were fetched.")
    public List<Citation> cite_sources(List<SearchResult> results, List<String> fetchedUrls) {
        return traceToolCall("cite_sources", () -> citeTool.cite(results, fetchedUrls));
    }

    private static final ThreadLocal<Boolean> IN_TOOL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private <T> T traceToolCall(String toolName, Supplier<T> action) {
        Tracer tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        if (tracer == null || IN_TOOL_CALL.get()) {
            return action.get();
        }
        IN_TOOL_CALL.set(true);
        Span span = tracer.nextSpan().name(toolName);
        Baggage baggage = tracer.getBaggage("task_id");
        if (baggage != null && baggage.get() != null) {
            span.tag("task_id", baggage.get());
        }
        span.start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return action.get();
        } catch (Throwable t) {
            span.error(t);
            throw t;
        } finally {
            try {
                span.end();
            } finally {
                IN_TOOL_CALL.set(false);
            }
        }
    }
}
