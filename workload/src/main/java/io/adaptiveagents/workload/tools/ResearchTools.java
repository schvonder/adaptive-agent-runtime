package io.adaptiveagents.workload.tools;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Spring AI tool surface exposed to agents. Each method delegates to a workload tool bean.
 */
@Component
public class ResearchTools {

    private final SearchTool searchTool;
    private final FetchTool fetchTool;
    private final CiteTool citeTool;

    public ResearchTools(SearchTool searchTool, FetchTool fetchTool, CiteTool citeTool) {
        this.searchTool = searchTool;
        this.fetchTool = fetchTool;
        this.citeTool = citeTool;
    }

    @Tool(name = "web_search",
            description = "Search the web for a query and return ranked results (title, url, snippet).")
    public List<SearchResult> web_search(String query) {
        return searchTool.search(query);
    }

    @Tool(name = "fetch_page",
            description = "Fetch the textual content of a web page by URL.")
    public String fetch_page(String url) {
        return fetchTool.fetch(url);
    }

    @Tool(name = "cite_sources",
            description = "Build a numbered citation list from search results and the URLs that were fetched.")
    public List<Citation> cite_sources(List<SearchResult> results, List<String> fetchedUrls) {
        return citeTool.cite(results, fetchedUrls);
    }
}
