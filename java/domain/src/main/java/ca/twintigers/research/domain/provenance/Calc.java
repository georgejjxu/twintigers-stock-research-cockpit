package ca.twintigers.research.domain.provenance;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A value derived from facts, with its formula and the inputs it used.
 *
 * @param value  null when an input is missing or the result is undefined; {@link #note()} says why
 * @param inputs input name to a Fact id or a number
 */
public record Calc(String name, BigDecimal value, String formula, Map<String, Object> inputs, String note)
        implements Cell {

    public Calc {
        inputs = inputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }

    public static Calc missing(String name, String formula, Map<String, Object> inputs, String note) {
        return new Calc(name, null, formula, inputs, note);
    }

    /** Reference to a cell for use as a calculation input: the Fact id, or the Calc's value. */
    public static Object ref(Cell cell) {
        return switch (cell) {
            case null -> null;
            case Fact f -> f.id();
            case Calc c -> c.value();
        };
    }
}
