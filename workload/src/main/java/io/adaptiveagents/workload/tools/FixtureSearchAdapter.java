package io.adaptiveagents.workload.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

/**
 * Fixture-backed {@link SearchTool}. Fixtures are recorded JSON arrays of
 * {@link SearchResult} stored on the classpath under {@code fixtures/search/} and are keyed
 * by the {@link Slug} of the query (e.g. {@code fixtures/search/spring-ai-tools.json}).
 *
 * <p>The fixture directory is enumerated via {@link ClassPathResource} at construction time;
 * a per-query {@link ClassPathResource} fallback keeps lookups working when the fixtures are
 * packaged inside a jar. A missing or unreadable fixture always yields an empty list.</p>
 */
public class FixtureSearchAdapter implements SearchTool {

    private static final Logger log = LoggerFactory.getLogger(FixtureSearchAdapter.class);
    private static final String DEFAULT_BASE = "fixtures/search/";
    private static final TypeReference<List<SearchResult>> RESULT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String basePath;
    private final Map<String, List<SearchResult>> fixtures;
    private final ObjectProvider<Tracer> tracerProvider;

    public FixtureSearchAdapter() {
        this(DEFAULT_BASE, null);
    }

    public FixtureSearchAdapter(ObjectProvider<Tracer> tracerProvider) {
        this(DEFAULT_BASE, tracerProvider);
    }

    public FixtureSearchAdapter(String basePath) {
        this(basePath, null);
    }

    public FixtureSearchAdapter(String basePath, ObjectProvider<Tracer> tracerProvider) {
        this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
        this.objectMapper = new ObjectMapper();
        this.tracerProvider = tracerProvider;
        // Resolve through this class's own classloader: in a Spring Boot fat jar the ambient
        // (context/system) classloader of a worker thread cannot see BOOT-INF/classes, which would
        // silently disable every packaged fixture.
        ClassLoader classLoader = FixtureSearchAdapter.class.getClassLoader();
        this.fixtures = loadFixtures(classLoader);
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

    /**
     * Slug keys longer than this are looked up by their truncated form; fixture files for
     * long queries are generated with the same truncation. Filesystems cap file names at
     * 255 bytes, so full task-prompt slugs (300+ chars) cannot exist as files otherwise.
     */
    static final int MAX_KEY_LENGTH = 200;

    private List<SearchResult> doSearch(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        for (String key : candidateKeys(Slug.of(query))) {
            List<SearchResult> cached = fixtures.get(key);
            if (cached != null) {
                log.debug("Fixture HIT: query='{}' -> key='{}'", query, key);
                return cached;
            }
            List<SearchResult> loaded = readFixtureResource(key);
            if (loaded != null) {
                log.debug("Fixture HIT (resource): query='{}' -> key='{}'", query, key);
                return loaded;
            }
        }
        log.debug("Fixture MISS: query='{}' (tried keys: {})", query, candidateKeys(Slug.of(query)));
        return List.of();
    }

    private static List<String> candidateKeys(String slug) {
        if (slug.length() <= MAX_KEY_LENGTH) {
            return List.of(slug);
        }
        return List.of(slug, slug.substring(0, MAX_KEY_LENGTH));
    }

    private List<SearchResult> readFixtureResource(String key) {
        ClassLoader classLoader = FixtureSearchAdapter.class.getClassLoader();
        ClassPathResource resource = new ClassPathResource(basePath + key + ".json", classLoader);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return readResults(in);
        } catch (Exception e) {
            log.warn("Failed to read search fixture '{}': {}", resource.getPath(), e.getMessage());
            return List.of();
        }
    }

    private Map<String, List<SearchResult>> loadFixtures(ClassLoader classLoader) {
        Map<String, List<SearchResult>> loaded = new HashMap<>();
        try {
            ClassPathResource directory = new ClassPathResource(basePath, classLoader);
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
                    loaded.put(key, readResults(in));
                } catch (Exception e) {
                    log.warn("Failed to read search fixture '{}': {}", file.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not enumerate search fixtures under '{}' at construction: {}",
                    basePath, e.getMessage());
        }
        return loaded;
    }

    private List<SearchResult> readResults(InputStream in) throws Exception {
        List<SearchResult> results = objectMapper.readValue(in, RESULT_LIST);
        return results == null ? List.of() : List.copyOf(results);
    }
}
