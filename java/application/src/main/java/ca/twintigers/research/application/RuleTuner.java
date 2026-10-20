package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.MarketContext;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.domain.market.Refinement;
import ca.twintigers.research.domain.market.RuleEngine;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.domain.market.Signal.Direction;
import ca.twintigers.research.domain.rules.Severity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tunes the warning and watch rules (directional ones) on history without fitting to it: each rule's variants (a small fixed menu, not a search)
 * are scored on the IN-SAMPLE period only; the best one is then checked on the OUT-OF-SAMPLE period it never saw.
 * A rule keeps its severity only if its chosen variant clears the hit-rate bar at both 5 and 20 trading days, with a
 * minimum number of cases, in BOTH periods. Every other one is demoted to info.
 * "Hit" = the close h days later went the rule's way (lower for a bearish rule, higher for a bullish one).
 */
public final class RuleTuner {

    public record Variant(String name, Refinement refinement) {
    }

    /** Statistics of one variant in one period. */
    public record Stats(int n, Double hit5, Double hit20, Double avg5, Double avg20) {
        boolean clears(double minHit, int minN) {
            return n >= minN && hit5 != null && hit20 != null && hit5 >= minHit && hit20 >= minHit;
        }

        double score() {
            return hit5 == null || hit20 == null ? -1 : Math.min(hit5, hit20);
        }
    }

    public record Result(Variant variant, Stats inSample, Stats outOfSample) {
    }

    /**
     * @param chosen the variant selected on in-sample data (null when none cleared the in-sample bar)
     * @param keep   whether the rule stays a warning (chosen variant also cleared the out-of-sample bar)
     */
    public record Decision(String ruleId, String ruleName, String direction, Result asDefined, Result chosen, boolean keep,
                           String reason, List<Result> tried) {
    }

    /** How often price simply fell (or rose) over h days, for context: the bar a bearish rule must beat. */
    public record BaseRate(String period, int n, double down5, double down20) {
    }

    public record Report(LocalDate from, LocalDate split, LocalDate to, int symbols, double minHit, int minInSample,
                         int minOutOfSample, List<Decision> decisions, List<BaseRate> baseRates) {
    }

    private final double minHit;
    private final int minInSample;
    private final int minOutOfSample;

    public RuleTuner(double minHit, int minInSample, int minOutOfSample) {
        this.minHit = minHit;
        this.minInSample = minInSample;
        this.minOutOfSample = minOutOfSample;
    }

    /** The fixed menu tried for a rule. Reversal (candlestick) rules also try a minimum prior move. */
    static List<Variant> variants(PriceRule r) {
        List<Variant> v = new ArrayList<>(List.of(
                new Variant("as defined", Refinement.NONE),
                new Variant("volume ≥ 2× average", new Refinement(2.0, null, null, false, false, null)),
                new Variant("with the 200-day trend", new Refinement(null, null, null, true, false, null)),
                new Variant("next-day confirmation", new Refinement(null, null, null, false, true, null)),
                new Variant("200-day trend + confirmation", new Refinement(null, null, null, true, true, null)),
                new Variant("volume ≥ 2× + 200-day trend", new Refinement(2.0, null, null, true, false, null))));
        if (r.id().startsWith("K")) {
            v.add(new Variant("prior 20-day move ≥ 5%", new Refinement(null, 20, 5.0, false, false, null)));
            v.add(new Variant("prior 20-day move ≥ 5% + confirmation", new Refinement(null, 20, 5.0, false, true, null)));
        }
        return v;
    }

    public Report tune(List<MarketContext> contexts, List<PriceRule> rules, LocalDate split) {
        List<Decision> decisions = new ArrayList<>();
        for (PriceRule base : rules) {
            if (base.severity() == Severity.INFO || base.direction() == Direction.NEUTRAL) {
                continue;
            }
            List<Result> tried = new ArrayList<>();
            for (Variant v : variants(base)) {
                tried.add(evaluate(contexts, v.refinement().apply(base), v, split));
            }
            Result asDefined = tried.getFirst();
            Result chosen = tried.stream().filter(t -> t.inSample().clears(minHit, minInSample))
                    .max(Comparator.comparingDouble((Result t) -> t.inSample().score())
                            .thenComparing(t -> -t.variant().name().length())) // prefer the simpler variant on ties
                    .orElse(null);
            boolean keep = chosen != null && chosen.outOfSample().clears(minHit, minOutOfSample);
            String reason = chosen == null
                    ? "no variant reached " + (int) minHit + "% at 5 and 20 days with " + minInSample + "+ cases in-sample"
                    : keep ? "chosen in-sample and confirmed out-of-sample"
                    : "best in-sample variant did not hold out-of-sample (" + fmt(chosen.outOfSample()) + ")";
            decisions.add(new Decision(base.id(), base.name(), base.direction().name().toLowerCase(java.util.Locale.ROOT),
                    asDefined, chosen, keep, reason, tried));
        }
        LocalDate from = contexts.stream().map(c -> c.bar(RuleEngine.WARMUP).date()).min(LocalDate::compareTo).orElse(null);
        LocalDate to = contexts.stream().map(c -> c.bar(c.size() - 1).date()).max(LocalDate::compareTo).orElse(null);
        return new Report(from, split, to, contexts.size(), minHit, minInSample, minOutOfSample, decisions, baseRates(contexts, split));
    }

    static String fmt(Stats s) {
        return s.n() + " cases, " + (s.hit5() == null ? "–" : s.hit5() + "%") + " / " + (s.hit20() == null ? "–" : s.hit20() + "%");
    }

    private Result evaluate(List<MarketContext> contexts, PriceRule rule, Variant v, LocalDate split) {
        double[][] acc = new double[2][6]; // per period: n5, hits5, sum5, n20, hits20, sum20
        RuleEngine engine = new RuleEngine(List.of(rule));
        boolean bear = rule.direction() == Direction.BEARISH;
        for (MarketContext ctx : contexts) {
            Map<String, Integer> idx = new LinkedHashMap<>();
            for (int i = 0; i < ctx.size(); i++) {
                idx.put(ctx.bar(i).date().toString(), i);
            }
            for (Signal s : engine.scan(ctx, RuleEngine.WARMUP)) {
                int i = idx.get(s.date().toString());
                int p = s.date().isBefore(split) ? 0 : 1;
                int[] hs = {5, 20};
                for (int h = 0; h < 2; h++) {
                    int j = i + hs[h];
                    if (j >= ctx.size()) {
                        continue;
                    }
                    double ret = (ctx.close[j] / ctx.close[i] - 1) * 100;
                    acc[p][h * 3] += 1;
                    acc[p][h * 3 + 1] += (bear ? ret < 0 : ret > 0) ? 1 : 0;
                    acc[p][h * 3 + 2] += ret;
                }
            }
        }
        return new Result(v, stats(acc[0]), stats(acc[1]));
    }

    private static Stats stats(double[] a) {
        return new Stats((int) a[0], a[0] == 0 ? null : r1(100 * a[1] / a[0]), a[3] == 0 ? null : r1(100 * a[4] / a[3]),
                a[0] == 0 ? null : r2(a[2] / a[0]), a[3] == 0 ? null : r2(a[5] / a[3]));
    }

    private static List<BaseRate> baseRates(List<MarketContext> contexts, LocalDate split) {
        double[][] acc = new double[2][4];
        for (MarketContext ctx : contexts) {
            for (int i = RuleEngine.WARMUP; i + 20 < ctx.size(); i++) {
                int p = ctx.bar(i).date().isBefore(split) ? 0 : 1;
                acc[p][0] += 1;
                acc[p][1] += ctx.close[i + 5] < ctx.close[i] ? 1 : 0;
                acc[p][2] += ctx.close[i + 20] < ctx.close[i] ? 1 : 0;
            }
        }
        return List.of(new BaseRate("in-sample", (int) acc[0][0], r1(100 * acc[0][1] / Math.max(1, acc[0][0])), r1(100 * acc[0][2] / Math.max(1, acc[0][0]))),
                new BaseRate("out-of-sample", (int) acc[1][0], r1(100 * acc[1][1] / Math.max(1, acc[1][0])), r1(100 * acc[1][2] / Math.max(1, acc[1][0]))));
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** rules.properties lines for the decisions: the chosen refinement for kept rules, severity=info for demoted ones. */
    public static List<String> configLines(Report r) {
        List<String> out = new ArrayList<>();
        for (Decision d : r.decisions()) {
            if (!d.keep()) {
                out.add("price.severity." + d.ruleId() + "=info");
                continue;
            }
            Refinement f = d.chosen().variant().refinement();
            String p = "price.refine." + d.ruleId() + ".";
            if (f.minRelVolume() != null) {
                out.add(p + "minRelVolume=" + f.minRelVolume());
            }
            if (f.priorReturnDays() != null) {
                out.add(p + "priorReturnDays=" + f.priorReturnDays());
                out.add(p + "priorReturnPct=" + f.priorReturnPct());
            }
            if (f.trendFilter()) {
                out.add(p + "trendFilter=true");
            }
            if (f.confirm()) {
                out.add(p + "confirm=true");
            }
        }
        return out;
    }
}
