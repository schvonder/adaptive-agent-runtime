package io.adaptiveagents.bench.runner;

import io.adaptiveagents.bench.config.BenchProfileConfig;
import io.adaptiveagents.bench.judge.LlmJudge;
import io.adaptiveagents.bench.report.BenchmarkReportGenerator;
import io.adaptiveagents.bench.report.BenchmarkReportGenerator.BenchmarkCell;
import io.adaptiveagents.core.envelope.TaskEnvelope;
import io.adaptiveagents.core.model.Topology;
import io.adaptiveagents.core.store.TaskStore;
import io.adaptiveagents.topologies.routing.RoutingService;
import io.adaptiveagents.topologies.runner.ExecutionRunner;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Executes the benchmark matrix (4 Topologies x 12 Tasks x N runs), collecting latency,
 * token/cost metrics, and LLM judge quality scores.
 */
@Component
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    public record BenchmarkTask(
            UUID taskId,
            String title,
            String prompt,
            int breadthHint
    ) {}

    private final TaskStore taskStore;
    private final RoutingService routingService;
    private final ExecutionRunner executionRunner;
    private final JdbcTemplate jdbcTemplate;

    public BenchmarkRunner(TaskStore taskStore, RoutingService routingService, ExecutionRunner executionRunner, JdbcTemplate jdbcTemplate) {
        this.taskStore = taskStore;
        this.routingService = routingService;
        this.executionRunner = executionRunner;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BenchmarkCell> runBenchmark(
            List<BenchmarkTask> tasks,
            BenchProfileConfig config,
            LlmJudge judge) {
        return runBenchmark(tasks, config, judge, List.of(Topology.SOLO, Topology.CHAIN, Topology.FAN_OUT, Topology.SUPERVISOR));
    }

    public List<BenchmarkCell> runBenchmark(
            List<BenchmarkTask> tasks,
            BenchProfileConfig config,
            LlmJudge judge,
            List<Topology> topologies) {

        List<BenchmarkCell> results = new ArrayList<>();
        int runs = Math.max(3, config.currentProfile().runs());
        BenchProfileConfig.ProfileEntry profile = config.currentProfile();

        for (BenchmarkTask task : tasks) {
            boolean fixturesPresent = checkFixtures(task.taskId());

            for (Topology topology : topologies) {
                if (!fixturesPresent) {
                    log.warn("Missing fixture for task {}; recording ERROR cell", task.taskId());
                    results.add(new BenchmarkCell(
                            task.taskId(),
                            task.title(),
                            topology,
                            runs,
                            0L,
                            BigDecimal.ZERO,
                            0.0,
                            "ERROR",
                            "missing fixture: fixtures/tasks/" + task.taskId() + "/search"
                    ));
                    continue;
                }

                List<Long> latencies = new ArrayList<>();
                List<BigDecimal> costs = new ArrayList<>();
                List<Integer> qualities = new ArrayList<>();
                String failureReason = null;

                for (int r = 1; r <= runs; r++) {
                    UUID runId = UUID.randomUUID();
                    TaskEnvelope envelope = new TaskEnvelope(
                            runId,
                            "research",
                            task.prompt(),
                            task.breadthHint(),
                            null,
                            null,
                            topology
                    );

                    long startNanos = System.nanoTime();
                    taskStore.submitOrGet(envelope);
                    routingService.route(envelope, System.currentTimeMillis());
                    executionRunner.executeAsync(runId);

                    String terminalStatus = awaitTerminal(runId, 120_000L);
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                    latencies.add(latencyMs);

                    String resultJson = jdbcTemplate.query(
                            "SELECT result::text FROM tasks WHERE task_id = ?",
                            rs -> rs.next() ? rs.getString(1) : null, runId);

                    BigDecimal runCost = readCost(runId, task.prompt(), resultJson, profile);
                    costs.add(runCost);

                    int quality = 0;
                    if ("SUCCEEDED".equals(terminalStatus) && resultJson != null) {
                        quality = judge.evaluate(task.prompt(), null, resultJson);
                    } else if (failureReason == null) {
                        failureReason = "run " + r + " " + terminalStatus;
                    }
                    qualities.add(quality);

                    log.info("Task {} ({}) [{}] run {}/{} -> {} ms, cost: ${}, quality: {}, status: {}",
                            task.taskId(), task.title(), topology, r, runs, latencyMs, runCost, quality, terminalStatus);
                }

                long p50Latency = medianLong(latencies);
                BigDecimal medianCost = medianBigDecimal(costs);
                double medianQuality = medianDouble(qualities);
                String cellStatus = failureReason == null ? "OK" : "ERROR";

                results.add(new BenchmarkCell(
                        task.taskId(),
                        task.title(),
                        topology,
                        runs,
                        p50Latency,
                        medianCost,
                        medianQuality,
                        cellStatus,
                        failureReason
                ));
                log.info("Cell finished: Task {} [{}] -> P50: {} ms, Cost: ${}, Quality: {}, Status: {}",
                        task.taskId(), topology, p50Latency, medianCost, medianQuality, cellStatus);
            }
        }

        return results;
    }

    private boolean checkFixtures(UUID taskId) {
        String path = "fixtures/tasks/" + taskId + "/search";
        ClassPathResource res = new ClassPathResource(path);
        if (res.exists()) {
            return true;
        }
        URL url = getClass().getClassLoader().getResource(path);
        return url != null;
    }

    private String awaitTerminal(UUID taskId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.query(
                    "SELECT status FROM tasks WHERE task_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, taskId);
            if (status != null && !"SUBMITTED".equals(status) && !"ROUTED".equals(status) && !"RUNNING".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for task " + taskId, e);
            }
        }
        return "TIMEOUT";
    }

    private BigDecimal readCost(UUID taskId, String prompt, String result, BenchProfileConfig.ProfileEntry profile) {
        long[] tokenCounts = jdbcTemplate.query(
                "SELECT COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0) FROM task_cost_entries WHERE task_id = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0L, 0L},
                taskId);
        if (tokenCounts != null && (tokenCounts[0] > 0 || tokenCounts[1] > 0)) {
            BigDecimal inputCost = BigDecimal.valueOf(tokenCounts[0]).multiply(profile.inputPerMillion())
                    .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
            BigDecimal outputCost = BigDecimal.valueOf(tokenCounts[1]).multiply(profile.outputPerMillion())
                    .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
            return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
        }

        BigDecimal recordedSpend = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(cost_usd), 0) FROM task_cost_entries WHERE task_id = ?",
                BigDecimal.class, taskId);
        if (recordedSpend != null && recordedSpend.compareTo(BigDecimal.ZERO) > 0) {
            return recordedSpend.setScale(6, RoundingMode.HALF_UP);
        }

        long promptTokens = prompt.length() / 4;
        long completionTokens = result == null ? 0 : result.length() / 4;

        BigDecimal inputCost = BigDecimal.valueOf(promptTokens).multiply(profile.inputPerMillion())
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).multiply(profile.outputPerMillion())
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
    }

    private static long medianLong(List<Long> values) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static BigDecimal medianBigDecimal(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double medianDouble(List<Integer> values) {
        if (values.isEmpty()) return 0.0;
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return (double) sorted.get(sorted.size() / 2);
    }
}
