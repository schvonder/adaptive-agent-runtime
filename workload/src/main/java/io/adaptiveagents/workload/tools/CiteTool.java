package io.adaptiveagents.workload.tools;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Pure transformation that turns search results into a numbered citation list.
 *
 * <p>When {@code fetchedUrls} is non-empty only results whose URL was fetched are cited
 * (the pages that were actually read). When {@code fetchedUrls} is {@code null} or empty
 * all results are cited, which supports search-only flows. Input order is preserved and
 * numbering is 1-based and stable.</p>
 */
@Component
public class CiteTool {

    private final ObjectProvider<Tracer> tracerProvider;

    @Autowired
    public CiteTool(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    public CiteTool() {
        this(null);
    }

    private static final ThreadLocal<Boolean> IN_TOOL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public List<Citation> cite(List<SearchResult> results, List<String> fetchedUrls) {
        Tracer tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        if (tracer == null || IN_TOOL_CALL.get()) {
            return doCite(results, fetchedUrls);
        }
        IN_TOOL_CALL.set(true);
        Span span = tracer.nextSpan().name("cite_sources");
        Baggage baggage = tracer.getBaggage("task_id");
        if (baggage != null && baggage.get() != null) {
            span.tag("task_id", baggage.get());
        }
        span.start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return doCite(results, fetchedUrls);
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

    private List<Citation> doCite(List<SearchResult> results, List<String> fetchedUrls) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        Set<String> fetched = (fetchedUrls == null) ? Set.of() : new HashSet<>(fetchedUrls);
        boolean filterToFetched = !fetched.isEmpty();

        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (SearchResult result : results) {
            if (result == null) {
                continue;
            }
            if (filterToFetched && !fetched.contains(result.url())) {
                continue;
            }
            citations.add(new Citation(index++, result.title(), result.url()));
        }
        return List.copyOf(citations);
    }
}
