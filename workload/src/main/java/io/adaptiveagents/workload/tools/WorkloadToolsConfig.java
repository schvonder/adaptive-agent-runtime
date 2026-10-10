package io.adaptiveagents.workload.tools;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the workload tools. Search is fixture-backed by default; the live Tavily adapter is
 * only registered when {@code WORKLOAD_LIVE=1} and a non-blank {@code TAVILY_API_KEY} are set.
 * The two search beans are mutually exclusive so no ordering-dependent conditional is needed.
 */
@Configuration
public class WorkloadToolsConfig {

    /** SpEL condition: live mode enabled AND a usable API key is present. */
    static final String LIVE_SEARCH_CONDITION =
            "T(java.lang.System).getenv('WORKLOAD_LIVE') == '1' "
                    + "and T(java.lang.System).getenv('TAVILY_API_KEY') != null "
                    + "and !T(java.lang.System).getenv('TAVILY_API_KEY').isBlank()";

    @Bean
    @ConditionalOnExpression(LIVE_SEARCH_CONDITION)
    public TavilyAdapter tavilyAdapter(ObjectProvider<Tracer> tracerProvider) {
        return new TavilyAdapter(System.getenv("TAVILY_API_KEY"), tracerProvider);
    }

    @Bean
    @ConditionalOnExpression("!(" + LIVE_SEARCH_CONDITION + ")")
    public FixtureSearchAdapter fixtureSearchAdapter(ObjectProvider<Tracer> tracerProvider) {
        return new FixtureSearchAdapter(tracerProvider);
    }

    @Bean
    public FetchTool fetchTool(ObjectProvider<Tracer> tracerProvider) {
        return new FixtureFetchAdapter(tracerProvider);
    }
}
