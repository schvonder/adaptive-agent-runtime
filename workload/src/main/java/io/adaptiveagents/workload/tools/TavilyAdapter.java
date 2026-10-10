package io.adaptiveagents.workload.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Live {@link SearchTool} backed by the Tavily search API
 * (<a href="https://api.tavily.com/search">https://api.tavily.com/search</a>).
 *
 * <p>This adapter is only registered when {@code WORKLOAD_LIVE=1} and {@code TAVILY_API_KEY}
 * are both present (see {@code WorkloadToolsConfig}). It performs a single request per call
 * and deliberately has no retry loop.</p>
 */
public class TavilyAdapter implements SearchTool {

    private static final Logger log = LoggerFactory.getLogger(TavilyAdapter.class);
    static final String SEARCH_URL = "https://api.tavily.com/search";

    private final String apiKey;
    private final RestClient restClient;
    private final ObjectProvider<Tracer> tracerProvider;

    public TavilyAdapter(String apiKey) {
        this(apiKey, RestClient.builder(), null);
    }

    public TavilyAdapter(String apiKey, ObjectProvider<Tracer> tracerProvider) {
        this(apiKey, RestClient.builder(), tracerProvider);
    }

    TavilyAdapter(String apiKey, RestClient.Builder builder) {
        this(apiKey, builder, null);
    }

    TavilyAdapter(String apiKey, RestClient.Builder builder, ObjectProvider<Tracer> tracerProvider) {
        this.apiKey = apiKey;
        this.restClient = builder.build();
        this.tracerProvider = tracerProvider;
    }

    private static final ThreadLocal<Boolean> IN_TOOL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public List<SearchResult> search(String query) {
        Tracer tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        if (tracer == null || IN_TOOL_CALL.get()) {
            return doSearch(query);
        }
        IN_TOOL_CALL.set(true);
        Span span = tracer.nextSpan().name("web_search");
        Baggage baggage = tracer.getBaggage("task_id");
        if (baggage != null && baggage.get() != null) {
            span.tag("task_id", baggage.get());
        }
        span.start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return doSearch(query);
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

    private List<SearchResult> doSearch(String query) {
        TavilyResponse response = restClient.post()
                .uri(SEARCH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TavilyRequest(apiKey, query))
                .retrieve()
                .body(TavilyResponse.class);

        if (response == null || response.results() == null) {
            log.debug("Tavily returned no results for query '{}'", query);
            return List.of();
        }
        return response.results().stream()
                .map(r -> new SearchResult(r.title(), r.url(), r.content()))
                .toList();
    }

    record TavilyRequest(@JsonProperty("api_key") String apiKey, String query) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(List<TavilyResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResult(String title, String url, String content) {
    }
}
