package io.adaptiveagents.workload.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    public List<Citation> cite(List<SearchResult> results, List<String> fetchedUrls) {
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
