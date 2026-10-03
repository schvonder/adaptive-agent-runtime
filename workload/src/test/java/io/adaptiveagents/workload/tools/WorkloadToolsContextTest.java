package io.adaptiveagents.workload.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkloadToolsContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkloadToolsConfig.class, CiteTool.class, ResearchTools.class);

    @Test
    void fixtureSearchIsActiveAndTavilyAbsentWhenLiveDisabled() {
        Assumptions.assumeTrue(!"1".equals(System.getenv("WORKLOAD_LIVE")),
                "WORKLOAD_LIVE=1 is set; skipping fixture-default assertion");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FixtureSearchAdapter.class);
            assertThat(context).doesNotHaveBean(TavilyAdapter.class);
            assertThat(context.getBean(SearchTool.class)).isInstanceOf(FixtureSearchAdapter.class);
            assertThat(context).hasSingleBean(FetchTool.class);
            assertThat(context).hasSingleBean(CiteTool.class);
            assertThat(context).hasSingleBean(ResearchTools.class);
        });
    }
}
