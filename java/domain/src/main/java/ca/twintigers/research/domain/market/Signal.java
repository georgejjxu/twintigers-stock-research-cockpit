package ca.twintigers.research.domain.market;

import ca.twintigers.research.domain.rules.Severity;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A price rule that fired on one day's bar.
 *
 * @param evidence the bar and indicator values that triggered the rule (name to number), so every number
 *                 in an explanation can be quoted from here rather than recomputed
 */
public record Signal(String symbol, String ruleId, String ruleName, Severity severity, Direction direction,
                     LocalDate date, String text, Map<String, Object> evidence) {

    public enum Direction { BULLISH, BEARISH, NEUTRAL }

    public Signal {
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
