package io.adaptiveagents.workload.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CiteToolTest {

    private final CiteTool citeTool = new CiteTool();
    private final SearchResult a = new SearchResult("A", "https://a.example", "sa");
    private final SearchResult b = new SearchResult("B", "https://b.example", "sb");
    private final SearchResult c = new SearchResult("C", "https://c.example", "sc");

    @Test
    void citesAllResultsInStableOrderWhenNoFetchedUrls() {
        List<Citation> citations = citeTool.cite(List.of(a, b, c), List.of());

        assertThat(citations).containsExactly(
                new Citation(1, "A", "https://a.example"),
                new Citation(2, "B", "https://b.example"),
                new Citation(3, "C", "https://c.example"));
    }

    @Test
    void filtersToFetchedUrlsAndKeepsStableNumbering() {
        List<Citation> citations = citeTool.cite(
                List.of(a, b, c),
                List.of("https://c.example", "https://a.example"));

        assertThat(citations).containsExactly(
                new Citation(1, "A", "https://a.example"),
                new Citation(2, "C", "https://c.example"));
    }

    @Test
    void emptySearchResultsReturnsEmpty() {
        assertThat(citeTool.cite(List.of(), List.of("https://a.example"))).isEmpty();
        assertThat(citeTool.cite(null, null)).isEmpty();
    }
}
