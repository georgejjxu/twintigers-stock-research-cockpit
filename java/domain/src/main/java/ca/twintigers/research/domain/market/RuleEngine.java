package ca.twintigers.research.domain.market;

import ca.twintigers.research.domain.market.Signal.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the price rules over a window of days, and replays them over history to measure how they behaved. */
public final class RuleEngine {

    /** Days of history the indicators need before rules are tested (MACD 26 + 9, plus slack). */
    public static final int WARMUP = 40;

    private final List<PriceRule> rules;

    public RuleEngine(List<PriceRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<PriceRule> rules() {
        return rules;
    }

    /** Every signal on days {@code from..end} (indices into the context), oldest first. */
    public List<Signal> scan(MarketContext ctx, int from) {
        List<Signal> out = new ArrayList<>();
        for (int i = Math.max(from, WARMUP); i < ctx.size(); i++) {
            for (PriceRule r : rules) {
                r.evaluate(ctx, i).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * How one rule behaved in a replay.
     *
     * @param fires           number of days it fired
     * @param avgReturn5d     mean % change of the close 5 trading days after the signal (signals without 5 later days excluded)
     * @param avgReturn20d    same over 20 days
     * @param hitRate5d       % of signals where the 5-day move went the rule's way (down for bearish, up for bullish); null for neutral rules
     * @param hitRate20d      same over 20 days
     */
    public record RuleStats(String ruleId, String ruleName, String severity, String direction, int fires,
                            Double avgReturn5d, Double avgReturn20d, Double hitRate5d, Double hitRate20d) {
    }

    public record Replay(int days, String from, String to, List<RuleStats> stats, List<Signal> signals) {
    }

    public Replay replay(MarketContext ctx) {
        List<Signal> signals = scan(ctx, WARMUP);
        Map<String, Integer> indexOfDate = new LinkedHashMap<>();
        for (int i = 0; i < ctx.size(); i++) {
            indexOfDate.put(ctx.bar(i).date().toString(), i);
        }
        List<RuleStats> stats = new ArrayList<>();
        for (PriceRule r : rules) {
            List<Signal> mine = signals.stream().filter(s -> s.ruleId().equals(r.id())).toList();
            double[] sum = new double[2];
            int[] n = new int[2];
            int[] hits = new int[2];
            int[] horizons = {5, 20};
            for (Signal s : mine) {
                int i = indexOfDate.get(s.date().toString());
                for (int h = 0; h < 2; h++) {
                    int j = i + horizons[h];
                    if (j < ctx.size()) {
                        double ret = (ctx.close[j] / ctx.close[i] - 1) * 100;
                        sum[h] += ret;
                        n[h]++;
                        if ((r.direction() == Direction.BEARISH && ret < 0) || (r.direction() == Direction.BULLISH && ret > 0)) {
                            hits[h]++;
                        }
                    }
                }
            }
            boolean directional = r.direction() != Direction.NEUTRAL;
            stats.add(new RuleStats(r.id(), r.name(), r.severity().label(), r.direction().name().toLowerCase(java.util.Locale.ROOT),
                    mine.size(),
                    n[0] == 0 ? null : PriceRules.r(sum[0] / n[0], 2), n[1] == 0 ? null : PriceRules.r(sum[1] / n[1], 2),
                    n[0] == 0 || !directional ? null : PriceRules.r(100.0 * hits[0] / n[0], 1),
                    n[1] == 0 || !directional ? null : PriceRules.r(100.0 * hits[1] / n[1], 1)));
        }
        String from = ctx.size() > WARMUP ? ctx.bar(WARMUP).date().toString() : null;
        String to = ctx.size() > 0 ? ctx.bar(ctx.size() - 1).date().toString() : null;
        return new Replay(Math.max(0, ctx.size() - WARMUP), from, to, stats, signals);
    }
}
