package io.adaptiveagents.bench.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;

/**
 * Parses and represents the benchmark configuration defined in {@code bench-profile.yml}:
 * one {@code model} branch for the agent-facing endpoint under benchmark and one full
 * {@code judge} branch for the quality-scoring judge.
 */
public record BenchProfileConfig(
        ModelEntry model,
        JudgeConfig judge
) {

    public record ModelEntry(
            String provider,
            String baseUrl,
            String model,
            String apiKeyEnv,
            double temperature,
            int seed,
            int runs,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion
    ) {}

    public record JudgeConfig(
            String baseUrl,
            String apiKeyEnv,
            String model,
            double temperature,
            int seed,
            String rubricScale,
            String rubricDescription
    ) {}

    public static BenchProfileConfig loadDefault() {
        return load("/bench-profile.yml");
    }

    public static BenchProfileConfig load(String resourcePath) {
        try (InputStream in = BenchProfileConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            return parse(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load benchmark profile configuration", e);
        }
    }

    public static BenchProfileConfig parse(InputStream in) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        JsonNode root = mapper.readTree(in);

        JsonNode modelNode = root.path("model");
        ModelEntry model = new ModelEntry(
                modelNode.path("provider").asText("openai-compatible"),
                requiredText(modelNode, "base-url", "model"),
                requiredText(modelNode, "model", "model"),
                requiredText(modelNode, "api-key-env", "model"),
                modelNode.path("temperature").asDouble(0.0),
                modelNode.path("seed").asInt(42),
                modelNode.path("runs").asInt(3),
                new BigDecimal(modelNode.path("pricing").path("input-per-1m").asText("0.15")),
                new BigDecimal(modelNode.path("pricing").path("output-per-1m").asText("0.60"))
        );

        JsonNode judgeNode = root.path("judge");
        JudgeConfig judge = new JudgeConfig(
                requiredText(judgeNode, "base-url", "judge"),
                requiredText(judgeNode, "api-key-env", "judge"),
                requiredText(judgeNode, "model", "judge"),
                judgeNode.path("temperature").asDouble(0.0),
                judgeNode.path("seed").asInt(42),
                judgeNode.path("rubric").path("scale").asText("0-5"),
                judgeNode.path("rubric").path("description").asText("Evaluates quality on a 0-5 scale.")
        );

        return new BenchProfileConfig(model, judge);
    }

    private static String requiredText(JsonNode node, String field, String context) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "bench-profile.yml: required field '" + field + "' is missing in " + context);
        }
        return value;
    }
}
