package io.adaptiveagents.workload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

/**
 * Fixture-backed {@link FetchTool}. Page fixtures are JSON objects with the fields
 * {@code url} and {@code content} stored on the classpath under {@code fixtures/pages/} and
 * are keyed by the {@link Slug} of the URL (e.g.
 * {@code fixtures/pages/https---example-com-spring-ai.json}).
 *
 * <p>The fixture directory is enumerated via {@link ClassPathResource} at construction time;
 * a per-URL {@link ClassPathResource} fallback keeps lookups working when the fixtures are
 * packaged inside a jar. A missing or unreadable page always yields an empty string.</p>
 */
public class FixtureFetchAdapter implements FetchTool {

    private static final Logger log = LoggerFactory.getLogger(FixtureFetchAdapter.class);
    private static final String DEFAULT_BASE = "fixtures/pages/";

    private final ObjectMapper objectMapper;
    private final String basePath;
    private final Map<String, String> pages;
    private final ObjectProvider<Tracer> tracerProvider;

    public FixtureFetchAdapter() {
        this(DEFAULT_BASE, null);
    }

    public FixtureFetchAdapter(ObjectProvider<Tracer> tracerProvider) {
        this(DEFAULT_BASE, tracerProvider);
    }

    public FixtureFetchAdapter(String basePath) {
        this(basePath, null);
    }

    public FixtureFetchAdapter(String basePath, ObjectProvider<Tracer> tracerProvider) {
        this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
        this.objectMapper = new ObjectMapper();
        this.tracerProvider = tracerProvider;
        this.pages = loadPages();
    }

    private static final ThreadLocal<Boolean> IN_TOOL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public String fetch(String url) {
        Tracer tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        if (tracer == null || IN_TOOL_CALL.get()) {
            return doFetch(url);
        }
        IN_TOOL_CALL.set(true);
        Span span = tracer.nextSpan().name("fetch_page");
        Baggage baggage = tracer.getBaggage("task_id");
        if (baggage != null && baggage.get() != null) {
            span.tag("task_id", baggage.get());
        }
        span.start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return doFetch(url);
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

    private String doFetch(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String slug = Slug.of(url);

        String cached = pages.get(slug);
        if (cached != null) {
            return cached;
        }

        ClassPathResource resource = new ClassPathResource(basePath + slug + ".json");
        if (!resource.exists()) {
            log.debug("No page fixture for url '{}' (expected {})", url, resource.getPath());
            return "";
        }
        try (InputStream in = resource.getInputStream()) {
            return readContent(in);
        } catch (Exception e) {
            log.warn("Failed to read page fixture '{}': {}", resource.getPath(), e.getMessage());
            return "";
        }
    }

    private Map<String, String> loadPages() {
        Map<String, String> loaded = new HashMap<>();
        try {
            ClassPathResource directory = new ClassPathResource(basePath);
            if (!directory.exists()) {
                return loaded;
            }
            File dir = directory.getFile();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) {
                return loaded;
            }
            for (File file : files) {
                String key = file.getName().substring(0, file.getName().length() - ".json".length());
                try (InputStream in = new FileInputStream(file)) {
                    loaded.put(key, readContent(in));
                } catch (Exception e) {
                    log.warn("Failed to read page fixture '{}': {}", file.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not enumerate page fixtures under '{}' at construction: {}",
                    basePath, e.getMessage());
        }
        return loaded;
    }

    private String readContent(InputStream in) throws Exception {
        PageFixture page = objectMapper.readValue(in, PageFixture.class);
        return page.content() == null ? "" : page.content();
    }

    /** Raw on-disk page fixture shape. */
    record PageFixture(String url, String content) {
    }
}
