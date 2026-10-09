package io.adaptiveagents.topologies.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable model price table loaded from {@code budget-prices.yml}: per-1M-token USD prices keyed
 * by model id, with a mandatory {@code default} entry that prices unlisted or absent model ids.
 *
 * <p>Fail-fast: a missing/blank file, a missing {@code models} mapping, a missing {@code default}
 * entry, or an entry without both {@code input-per-1m} and {@code output-per-1m} decimals is a
 * startup error, never a silent zero price.</p>
 */
public final class PriceTable {

    private static final String DEFAULT_MODEL = "default";
    private static final String RESOURCE = "/budget-prices.yml";
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);
    private static final int COST_SCALE = 12;

    /** Per-1M-token USD prices for one model. */
    public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {}

    private final Map<String, ModelPrice> prices;

    private PriceTable(Map<String, ModelPrice> prices) {
        this.prices = prices;
    }

    public static PriceTable fromClasspath() {
        try (InputStream input = PriceTable.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("budget-prices.yml not found on the classpath");
            }
            return from(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read budget-prices.yml", e);
        }
    }

    public static PriceTable from(InputStream input) {
        JsonNode root = read(input);
        JsonNode models = root.get("models");
        if (models == null || !models.isObject()) {
            throw new IllegalStateException("budget-prices.yml must contain a 'models' mapping");
        }
        Map<String, ModelPrice> parsed = new HashMap<>();
        models.properties().forEach(entry -> parsed.put(entry.getKey(), parsePrice(entry.getKey(), entry.getValue())));
        if (!parsed.containsKey(DEFAULT_MODEL)) {
            throw new IllegalStateException("budget-prices.yml must define a '" + DEFAULT_MODEL + "' entry");
        }
        return new PriceTable(Map.copyOf(parsed));
    }

    private static JsonNode read(InputStream input) {
        try {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(input);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("budget-prices.yml must be a mapping");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse budget-prices.yml", e);
        }
    }

    private static ModelPrice parsePrice(String model, JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalStateException("price entry '" + model + "' must be a mapping");
        }
        return new ModelPrice(
                decimal(model, node, "input-per-1m"),
                decimal(model, node, "output-per-1m"));
    }

    private static BigDecimal decimal(String model, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("price entry '" + model + "' must define numeric '" + field + "'");
        }
        return value.decimalValue();
    }

    /**
     * USD cost of one model response: {@code tokens/1M × per-1M price}, summed over input and
     * output tokens. Half-up at scale 12; never negative.
     */
    public BigDecimal costUsd(String model, long promptTokens, long completionTokens) {
        ModelPrice price = priceFor(model);
        return cost(promptTokens, price.inputPerMillion())
                .add(cost(completionTokens, price.outputPerMillion()));
    }

    private static BigDecimal cost(long tokens, BigDecimal perMillion) {
        return BigDecimal.valueOf(tokens).multiply(perMillion)
                .divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }

    private ModelPrice priceFor(String model) {
        ModelPrice price = model == null || model.isBlank() ? null : prices.get(model);
        return price != null ? price : prices.get(DEFAULT_MODEL);
    }
}
