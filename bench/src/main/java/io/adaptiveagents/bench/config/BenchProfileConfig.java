package io.adaptiveagents.bench.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses and represents the benchmark configuration defined in {@code bench-profile.yml}.
 */
public record BenchProfileConfig(
        String activeProfile,
        Map<String, ProfileEntry> profiles,
        JudgeConfig judge
) {

    public record ProfileEntry(
            String name,
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
            String model,
            double temperature,
            int seed,
            String rubricScale,
            String rubricDescription
    ) {}

    public ProfileEntry currentProfile() {
        ProfileEntry entry = profiles.get(activeProfile);
        if (entry == null) {
            throw new IllegalStateException("Active profile '" + activeProfile + "' not found in profiles");
        }
        return entry;
    }

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

        String active = root.path("active-profile").asText("vertex-proxy");

        JsonNode profilesNode = root.path("profiles");
        Map<String, ProfileEntry> profiles = new HashMap<>();
        if (profilesNode.isObject()) {
            profilesNode.properties().forEach(entry -> {
                String profileName = entry.getKey();
                JsonNode node = entry.getValue();
                ProfileEntry pe = new ProfileEntry(
                        profileName,
                        node.path("provider").asText("openai-compatible"),
                        node.path("base-url").asText("http://localhost:4000"),
                        node.path("model").asText("gemini-3.8-flash-vertex"),
                        node.path("api-key-env").asText("RUNTIME_BENCH_API_KEY"),
                        node.path("temperature").asDouble(0.0),
                        node.path("seed").asInt(42),
                        node.path("runs").asInt(3),
                        new BigDecimal(node.path("pricing").path("input-per-1m").asText("0.15")),
                        new BigDecimal(node.path("pricing").path("output-per-1m").asText("0.60"))
                );
                profiles.put(profileName, pe);
            });
        }

        JsonNode judgeNode = root.path("judge");
        JudgeConfig judge = new JudgeConfig(
                judgeNode.path("model").asText("gemini-3.8-flash-vertex"),
                judgeNode.path("temperature").asDouble(0.0),
                judgeNode.path("seed").asInt(42),
                judgeNode.path("rubric").path("scale").asText("0-5"),
                judgeNode.path("rubric").path("description").asText("Evaluates quality on a 0-5 scale.")
        );

        return new BenchProfileConfig(active, Map.copyOf(profiles), judge);
    }
}
