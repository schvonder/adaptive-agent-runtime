package io.adaptiveagents.workload.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FixtureSearchAdapterTest {

    private final FixtureSearchAdapter adapter = new FixtureSearchAdapter();

    @Test
    void fixtureQueryReturnsParsedResults() {
        List<SearchResult> results = adapter.search("spring ai tools");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).title()).isEqualTo("Spring AI Tool Calling");
        assertThat(results.get(0).url())
                .isEqualTo("https://docs.spring.io/spring-ai/reference/api/tools.html");
        assertThat(results.get(0).snippet()).isNotBlank();
    }

    @Test
    void slugNormalizationIsCaseInsensitiveAndPunctuationAware() {
        // "Adaptive Agents" -> "adaptive-agents"
        assertThat(adapter.search("Adaptive Agents")).hasSize(2);
    }

    @Test
    void missingFixtureReturnsEmptyList() {
        assertThat(adapter.search("no such fixture")).isEmpty();
    }

    @Test
    void blankOrNullQueryReturnsEmptyList() {
        assertThat(adapter.search("   ")).isEmpty();
        assertThat(adapter.search(null)).isEmpty();
    }
}
