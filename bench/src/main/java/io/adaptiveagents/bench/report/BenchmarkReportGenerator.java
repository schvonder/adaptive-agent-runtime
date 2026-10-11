package io.adaptiveagents.bench.report;

import io.adaptiveagents.bench.config.BenchProfileConfig;
import io.adaptiveagents.core.model.Topology;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Generates the benchmark markdown report (BENCHMARK.md) from matrix results.
 */
public class BenchmarkReportGenerator {

    public record BenchmarkCell(
            UUID taskId,
            String taskTitle,
            Topology topology,
            int runs,
            long p50LatencyMs,
            BigDecimal medianCost,
            double medianQuality,
            String status,
            String errorReason
    ) {}

    public static String generateMarkdown(BenchProfileConfig config, List<BenchmarkCell> cells) {
        StringBuilder sb = new StringBuilder();
        BenchProfileConfig.ProfileEntry profile = config.currentProfile();

        sb.append("# Multi-Agent Topology Benchmark Results\n\n");
        sb.append("## Benchmark Configuration\n");
        sb.append("- **Timestamp**: ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("\n");
        sb.append("- **Active Profile**: `").append(config.activeProfile()).append("`\n");
        sb.append("- **Provider**: ").append(profile.provider()).append("\n");
        sb.append("- **Base URL**: ").append(profile.baseUrl()).append("\n");
        sb.append("- **Model**: `").append(profile.model()).append("`\n");
        sb.append("- **API Key Reference**: `").append(profile.apiKeyEnv()).append("`\n");
        sb.append("- **Temperature**: ").append(profile.temperature()).append("\n");
        sb.append("- **Seed**: ").append(profile.seed()).append("\n");
        sb.append("- **Runs per Cell (N)**: ").append(profile.runs()).append(" (reporting medians / p50)\n");
        sb.append("- **Pricing**: $").append(profile.inputPerMillion()).append(" / 1M input, $")
                .append(profile.outputPerMillion()).append(" / 1M output\n");
        sb.append("- **Judge Model**: `").append(config.judge().model()).append("` (Rubric ").append(config.judge().rubricScale()).append(")\n\n");

        sb.append("## Results Matrix (4 Topologies x Tasks x N=").append(profile.runs()).append(" runs)\n\n");
        sb.append("| Task ID | Task Title | Topology | P50 Latency (ms) | Median Cost ($) | Quality (0-5) | Status |\n");
        sb.append("|---|---|---|---|---|---|---|\n");

        for (BenchmarkCell cell : cells) {
            String latencyCol = "OK".equals(cell.status()) ? String.valueOf(cell.p50LatencyMs()) : "-";
            String costCol = "OK".equals(cell.status()) ? cell.medianCost().toPlainString() : "-";
            String qualityCol = "OK".equals(cell.status()) ? String.format("%.1f", cell.medianQuality()) : "-";
            String statusCol = "OK".equals(cell.status()) ? "OK" : "ERROR (" + cell.errorReason() + ")";

            sb.append("| `").append(cell.taskId()).append("` ")
                    .append("| ").append(cell.taskTitle()).append(" ")
                    .append("| `").append(cell.topology()).append("` ")
                    .append("| ").append(latencyCol).append(" ")
                    .append("| ").append(costCol).append(" ")
                    .append("| ").append(qualityCol).append(" ")
                    .append("| ").append(statusCol).append(" |\n");
        }

        return sb.toString();
    }

    public static String generateFullReport(BenchProfileConfig config, List<BenchmarkCell> cells, String analysis, String reproduction) {
        StringBuilder sb = new StringBuilder(generateMarkdown(config, cells));
        if (analysis != null && !analysis.isBlank()) {
            sb.append("\n## Analysis\n\n").append(analysis.trim()).append("\n");
        }
        if (reproduction != null && !reproduction.isBlank()) {
            sb.append("\n## Reproduction Instructions\n\n").append(reproduction.trim()).append("\n");
        }
        return sb.toString();
    }

    public static void writeToFile(Path destination, BenchProfileConfig config, List<BenchmarkCell> cells) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        String markdown = generateMarkdown(config, cells);
        Files.writeString(destination, markdown, StandardCharsets.UTF_8);
    }

    public static void writeFullReportToFile(Path destination, BenchProfileConfig config, List<BenchmarkCell> cells, String analysis, String reproduction) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        String markdown = generateFullReport(config, cells, analysis, reproduction);
        Files.writeString(destination, markdown, StandardCharsets.UTF_8);
    }
}
