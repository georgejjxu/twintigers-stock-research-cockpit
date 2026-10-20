package ca.twintigers.research.adapters.sec;

import ca.twintigers.research.domain.fundamentals.FactBook;
import ca.twintigers.research.domain.fundamentals.HealthTable;
import ca.twintigers.research.domain.fundamentals.HealthTableBuilder;
import ca.twintigers.research.domain.fundamentals.Thresholds;
import ca.twintigers.research.domain.provenance.Cell;
import ca.twintigers.research.domain.rules.Flag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline parity with the Python reference: the synthetic company in research-tool's tests (restatement,
 * tag switch, 10-Q YTD) must give the same table, cell for cell, and the same ported flags.
 * The golden file was produced by research_core.health.build_health_table on the same JSON.
 */
class GoldenParityTest {

    private static String resource(String name) throws IOException {
        try (var in = Objects.requireNonNull(GoldenParityTest.class.getResourceAsStream("/golden/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void healthTableMatchesPythonCellForCell() throws IOException {
        HealthTable t = new HealthTableBuilder(new FactBook(SecEdgarClient.parse(resource("CIK0009999999.json"))),
                Thresholds.defaults()).build(3, null);
        JsonNode py = new ObjectMapper().readTree(resource("CIK0009999999.python-health.json"));

        assertThat(t.columns()).hasSize(py.path("columns").size());
        for (int i = 0; i < t.columns().size(); i++) {
            assertThat(t.columns().get(i).period().end().toString()).isEqualTo(py.path("columns").path(i).path("end").asString());
            assertThat(t.columns().get(i).kind().name()).isEqualTo(py.path("columns").path(i).path("kind").asString());
        }
        int compared = 0;
        for (var row : t.rows().entrySet()) {
            JsonNode prow = py.path("rows").path(row.getKey());
            assertThat(prow.size()).as(row.getKey()).isEqualTo(row.getValue().size());
            for (int i = 0; i < prow.size(); i++) {
                Cell c = row.getValue().get(i);
                BigDecimal java = c == null ? null : c.value();
                BigDecimal python = prow.path(i).isNumber() ? prow.path(i).decimalValue() : null;
                if (python == null) {
                    assertThat(java).as(row.getKey() + " col " + i).isNull();
                } else {
                    assertThat(java).as(row.getKey() + " col " + i).isNotNull();
                    assertThat(java.compareTo(python)).as(row.getKey() + " col " + i + ": java " + java + " python " + python).isZero();
                }
                compared++;
            }
        }
        assertThat(compared).isEqualTo(17 * 5);
        assertThat(t.currency()).isEqualTo(py.path("currency").asString());

        List<String> pyFlags = py.path("flags").values().stream()
                .map(f -> f.path("severity").asString() + ": " + f.path("flag").asString()).toList();
        List<String> javaPorted = t.flags().stream().filter(f -> f.ruleId().compareTo("F08") < 0)
                .map(f -> f.severity().label() + ": " + f.text()).toList();
        assertThat(javaPorted).containsExactlyElementsOf(pyFlags);
        assertThat(t.flags()).extracting(Flag::ruleId).startsWith("F02_ytd_revenue_decline");
    }
}
