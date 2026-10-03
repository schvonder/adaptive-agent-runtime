package io.adaptiveagents.workload.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchToolsTest {

    private final ResearchTools tools =
            new ResearchTools(new FixtureSearchAdapter(), new FixtureFetchAdapter(), new CiteTool());

    @Test
    void webSearchDelegatesToFixtureSearch() {
        assertThat(tools.web_search("spring ai tools")).hasSize(3);
    }

    @Test
    void fetchPageDelegatesToFixtureFetch() {
        assertThat(tools.fetch_page("https://docs.example.org/tavily")).contains("api_key");
    }

    @Test
    void citeSourcesDelegatesToCiteTool() {
        List<SearchResult> results = tools.web_search("adaptive agents");
        List<String> fetched = List.of(results.get(0).url());

        List<Citation> citations = tools.cite_sources(results, fetched);

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).index()).isEqualTo(1);
        assertThat(citations.get(0).url()).isEqualTo(results.get(0).url());
    }
}
