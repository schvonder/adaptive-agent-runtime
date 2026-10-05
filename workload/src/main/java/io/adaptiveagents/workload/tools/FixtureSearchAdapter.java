package io.adaptiveagents.workload.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public FixtureSearchAdapter() {
        this(DEFAULT_BASE);
    }

    public FixtureSearchAdapter(String basePath) {
        this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
        this.objectMapper = new ObjectMapper();
        // Resolve through this class's own classloader: in a Spring Boot fat jar the ambient
        // (context/system) classloader of a worker thread cannot see BOOT-INF/classes, which would
        // silently disable every packaged fixture.
        ClassLoader classLoader = FixtureSearchAdapter.class.getClassLoader();
        this.fixtures = loadFixtures(classLoader);
    }

    @Override
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String slug = Slug.of(query);

        List<SearchResult> cached = fixtures.get(slug);
        if (cached != null) {
            return cached;
        }

        ClassLoader classLoader = FixtureSearchAdapter.class.getClassLoader();
        ClassPathResource resource = new ClassPathResource(basePath + slug + ".json", classLoader);
        if (!resource.exists()) {
            log.debug("No search fixture for query '{}' (expected {})", query, resource.getPath());
            return List.of();
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
