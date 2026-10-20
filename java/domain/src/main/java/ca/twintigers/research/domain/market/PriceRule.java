package ca.twintigers.research.domain.market;

import ca.twintigers.research.domain.market.Signal.Direction;
import ca.twintigers.research.domain.rules.Severity;

import java.util.Map;
import java.util.Optional;

/**
 * One price rule: tested on day i of a {@link MarketContext}, using only data up to and including day i
 * (no look-ahead), so a replay over history gives the same results the rule would have given live.
 *
 * @param definition plain-English definition, also used for the rule catalog
 */
public record PriceRule(String id, String name, Severity severity, Direction direction, String definition,
                        Evaluator evaluator) {

    /** What the rule found on a day: the sentence and the numbers behind it. */
    public record Hit(String text, Map<String, Object> evidence) {
    }

    @FunctionalInterface
    public interface Evaluator {
        Optional<Hit> test(MarketContext ctx, int i);
    }

    public Optional<Signal> evaluate(MarketContext ctx, int i) {
        return evaluator.test(ctx, i).map(h -> new Signal(ctx.symbol, id, name, severity, direction,
                ctx.bar(i).date(), h.text(), h.evidence()));
    }
}
