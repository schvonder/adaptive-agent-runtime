package io.adaptiveagents.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.bench.config.BenchProfileConfig;
import io.adaptiveagents.bench.judge.LlmJudge;
import io.adaptiveagents.bench.report.BenchmarkReportGenerator;
import io.adaptiveagents.bench.report.BenchmarkReportGenerator.BenchmarkCell;
import io.adaptiveagents.bench.runner.BenchmarkRunner;
import io.adaptiveagents.bench.runner.BenchmarkRunner.BenchmarkTask;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.workload.tools.FetchTool;
import io.adaptiveagents.workload.tools.FixtureFetchAdapter;
import io.adaptiveagents.workload.tools.FixtureSearchAdapter;
import io.adaptiveagents.workload.tools.SearchTool;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = BenchmarkRunnerTest.Config.class, properties = {
        "spring.ai.model.chat=none"
})
@ActiveProfiles("stub-classifier")
@Testcontainers
class BenchmarkRunnerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @SpringBootApplication(scanBasePackages = "io.adaptiveagents")
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure().dataSource(dataSource).load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        ChatModel chatModel() {
            return new FixedTextChatModel("{\"plan\":\"canned plan\",\"summary\":\"canned research summary\",\"citations\":[]}");
        }
    }

    static final class FixedTextChatModel implements ChatModel {
        private final String text;

        FixedTextChatModel(String text) {
            this.text = text;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    BenchmarkRunner benchmarkRunner;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE tasks CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS lg4jcheckpoint, lg4jthread CASCADE");
    }

    @Test
    @Timeout(120)
    void benchmarkMatrixExecutionAndReport() throws Exception {
        ClassPathResource resource = new ClassPathResource("tasks/demo-tasks.json");
        assertThat(resource.exists()).isTrue();

        List<TaskEnvelope> demoEnvelopes;
        try (InputStream in = resource.getInputStream()) {
            demoEnvelopes = objectMapper.readValue(in, new TypeReference<List<TaskEnvelope>>() {});
        }
        assertThat(demoEnvelopes).hasSize(12);

        List<BenchmarkTask> allTasks = new ArrayList<>();
        for (int i = 0; i < demoEnvelopes.size(); i++) {
            TaskEnvelope env = demoEnvelopes.get(i);
            allTasks.add(new BenchmarkTask(env.taskId(), "Task " + (i + 1), env.prompt(), env.breadthHint()));
        }

        UUID missingFixtureId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        allTasks.add(new BenchmarkTask(missingFixtureId, "Missing Fixture Test Task", "test prompt without fixtures", 1));

        BenchProfileConfig config = BenchProfileConfig.loadDefault();
        LlmJudge judge = new LlmJudge();

        List<BenchmarkCell> cells = benchmarkRunner.runBenchmark(allTasks, config, judge);

        assertThat(cells).hasSize(13 * 4);

        List<BenchmarkCell> demoCells = cells.stream()
                .filter(c -> !missingFixtureId.equals(c.taskId()))
                .toList();
        assertThat(demoCells).hasSize(12 * 4);

        for (BenchmarkCell cell : demoCells) {
            assertThat(cell.status()).isEqualTo("OK");
            assertThat(cell.runs()).isEqualTo(3);
            assertThat(cell.p50LatencyMs()).isGreaterThan(0L);
            assertThat(cell.medianCost()).isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO);
            assertThat(cell.medianQuality()).isGreaterThanOrEqualTo(0.0);
        }

        List<BenchmarkCell> errorCells = cells.stream()
                .filter(c -> missingFixtureId.equals(c.taskId()))
                .toList();
        assertThat(errorCells).hasSize(4);
        for (BenchmarkCell errorCell : errorCells) {
            assertThat(errorCell.status()).isEqualTo("ERROR");
            assertThat(errorCell.errorReason()).contains("missing fixture");
        }

        String markdown = BenchmarkReportGenerator.generateMarkdown(config, cells);
        assertThat(markdown).contains("Multi-Agent Topology Benchmark Results");
        assertThat(markdown).contains("Benchmark Configuration");
        assertThat(markdown).contains("Active Profile", "vertex-proxy");
        assertThat(markdown).contains(config.currentProfile().model());
        assertThat(markdown).contains("RUNTIME_BENCH_API_KEY");
        assertThat(markdown).contains("Runs per Cell (N)");
        assertThat(markdown).contains("ERROR (missing fixture:");

        Path outputPath = Path.of("target/BENCHMARK.md");
        BenchmarkReportGenerator.writeToFile(outputPath, config, demoCells);
        assertThat(Files.exists(outputPath)).isTrue();
        assertThat(Files.readString(outputPath)).contains(config.currentProfile().model());
    }
}
