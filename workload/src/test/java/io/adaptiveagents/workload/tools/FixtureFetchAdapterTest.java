package io.adaptiveagents.workload.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixtureFetchAdapterTest {

    private final FixtureFetchAdapter adapter = new FixtureFetchAdapter();

    @Test
    void fixtureUrlReturnsContent() {
        String content = adapter.fetch("https://example.com/spring-ai");

        assertThat(content).contains("Spring AI").contains("@Tool");
    }

    @Test
    void secondFixtureUrlReturnsContent() {
        String content = adapter.fetch("https://docs.example.org/tavily");

        assertThat(content).contains("api_key").contains("query");
    }

    @Test
    void missingPageReturnsEmptyString() {
        assertThat(adapter.fetch("https://example.com/does-not-exist")).isEmpty();
    }

    @Test
    void blankOrNullUrlReturnsEmptyString() {
        assertThat(adapter.fetch("")).isEmpty();
        assertThat(adapter.fetch(null)).isEmpty();
    }
}
