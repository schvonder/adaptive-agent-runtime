package io.adaptiveagents.topologies.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PriceTableTest {

    @Test
    void computesCostFromPerMillionPrices() {
        PriceTable table = table("""
                models:
                  default:
                    input-per-1m: 1.00
                    output-per-1m: 2.00
                  gpt-4o-mini:
                    input-per-1m: 0.15
                    output-per-1m: 0.60
                """);

        assertThat(table.costUsd("gpt-4o-mini", 1_000_000, 500_000)).isEqualByComparingTo("0.45");
        assertThat(table.costUsd("gpt-4o-mini", 0, 0)).isEqualByComparingTo("0");
        assertThat(table.costUsd("default", 250_000, 250_000)).isEqualByComparingTo("0.75");
    }

    @Test
    void unlistedOrNullModelFallsBackToDefaultEntry() {
        PriceTable table = table("""
                models:
                  default:
                    input-per-1m: 1.00
                    output-per-1m: 2.00
                """);

        assertThat(table.costUsd("totally-unknown", 1_000_000, 0)).isEqualByComparingTo("1.00");
        assertThat(table.costUsd(null, 0, 1_000_000)).isEqualByComparingTo("2.00");
        assertThat(table.costUsd("", 1_000_000, 0)).isEqualByComparingTo("1.00");
    }

    @Test
    void classpathTableLoadsAndPricesGpt4oMini() {
        PriceTable table = PriceTable.fromClasspath();

        assertThat(table.costUsd("gpt-4o-mini", 1_000_000, 1_000_000)).isEqualByComparingTo("0.75");
    }

    @Test
    void missingDefaultEntryFailsFast() {
        assertThatThrownBy(() -> table("""
                models:
                  gpt-4o-mini:
                    input-per-1m: 0.15
                    output-per-1m: 0.60
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default");
    }

    @Test
    void incompletePriceEntryFailsFast() {
        assertThatThrownBy(() -> table("""
                models:
                  default:
                    input-per-1m: 1.00
                    output-per-1m: 2.00
                  broken:
                    input-per-1m: 0.15
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("output-per-1m");
    }

    private static PriceTable table(String yaml) {
        return PriceTable.from(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
