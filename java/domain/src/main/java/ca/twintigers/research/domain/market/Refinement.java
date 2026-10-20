package ca.twintigers.research.domain.market;

import ca.twintigers.research.domain.market.Signal.Direction;
import ca.twintigers.research.domain.rules.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stricter conditions on top of a rule, chosen by the replay tuner and set in rules.properties
 * ({@code price.refine.<ruleId>.<field>}). All are optional:
 *
 * @param minRelVolume    the signal day's volume must be at least this × its prior average
 * @param priorReturnDays together with priorReturnPct: before a bearish signal the close must have risen at least
 * @param priorReturnPct  priorReturnPct % over priorReturnDays days (fallen, for a bullish one): a real trend to reverse
 * @param trendFilter     bearish only below the 200-day average, bullish only above it (trade with the long trend)
 * @param confirm         the next day must confirm: close below the signal day's low (bearish) or above its high
 *                        (bullish); the signal is then reported on the confirming day
 * @param severity        severity override (e.g. INFO to demote a rule that the replay found uninformative)
 */
public record Refinement(Double minRelVolume, Integer priorReturnDays, Double priorReturnPct, boolean trendFilter,
                         boolean confirm, Severity severity) {

    public static final Refinement NONE = new Refinement(null, null, null, false, false, null);

    public boolean isNone() {
        return minRelVolume == null && priorReturnDays == null && !trendFilter && !confirm;
    }

    /** Short label, e.g. "rvol≥2 + trend + confirm". */
    public String label() {
        StringBuilder b = new StringBuilder();
        if (minRelVolume != null) {
            b.append("rvol≥").append(fmt(minRelVolume)).append(" + ");
        }
        if (priorReturnDays != null) {
            b.append("prior ").append(priorReturnDays).append("d move≥").append(fmt(priorReturnPct)).append("% + ");
        }
        if (trendFilter) {
            b.append("200-day trend + ");
        }
        if (confirm) {
            b.append("next-day confirm + ");
        }
        return b.isEmpty() ? "as defined" : b.substring(0, b.length() - 3);
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Rule with the refinement (and severity override) applied. */
    public PriceRule apply(PriceRule base) {
        Severity sev = severity != null ? severity : base.severity();
        if (isNone()) {
            return sev == base.severity() ? base : new PriceRule(base.id(), base.name(), sev, base.direction(), base.definition(), base.evaluator());
        }
        String def = base.definition() + " Refined: " + label() + ".";
        return new PriceRule(base.id(), base.name(), sev, base.direction(), def, (ctx, i) -> test(base, ctx, i));
    }

    private Optional<PriceRule.Hit> test(PriceRule base, MarketContext x, int i) {
        int j = confirm ? i - 1 : i; // the bar the base pattern must fire on
        if (j < 1) {
            return Optional.empty();
        }
        Optional<PriceRule.Hit> hit = base.evaluator().test(x, j);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        boolean bear = base.direction() == Direction.BEARISH;
        boolean bull = base.direction() == Direction.BULLISH;
        Map<String, Object> ev = new LinkedHashMap<>(hit.get().evidence());
        StringBuilder extra = new StringBuilder();
        if (minRelVolume != null) {
            double rv = x.relVolume(j);
            if (Double.isNaN(rv) || rv < minRelVolume) {
                return Optional.empty();
            }
            ev.put("refine_rel_volume", PriceRules.r(rv, 2));
        }
        if (priorReturnDays != null && priorReturnPct != null) {
            int from = j - 1 - priorReturnDays;
            if (from < 0) {
                return Optional.empty();
            }
            double move = (x.close[j - 1] / x.close[from] - 1) * 100;
            if ((bear && move < priorReturnPct) || (bull && move > -priorReturnPct)) {
                return Optional.empty();
            }
            ev.put("refine_prior_move_pct", PriceRules.r(move, 2));
            ev.put("refine_prior_days", priorReturnDays);
        }
        if (trendFilter) {
            double sma = x.sma200[j];
            if (Double.isNaN(sma) || (bear && x.close[j] >= sma) || (bull && x.close[j] <= sma)) {
                return Optional.empty();
            }
            ev.put("refine_sma200", PriceRules.r(sma, 4));
            extra.append(bear ? " Below" : " Above").append(" the 200-day average ").append(PriceRules.f2(sma)).append('.');
        }
        if (confirm) {
            Bar s = x.bar(j);
            Bar c = x.bar(i);
            boolean ok = bear ? c.close() < s.low() : bull ? c.close() > s.high() : false;
            if (!ok) {
                return Optional.empty();
            }
            ev.put("refine_signal_date", s.date().toString());
            ev.put("refine_confirm_close", PriceRules.r(c.close(), 4));
            ev.put(bear ? "refine_signal_low" : "refine_signal_high", PriceRules.r(bear ? s.low() : s.high(), 4));
            extra.append(" Confirmed on ").append(c.date()).append(": close ").append(PriceRules.f2(c.close()))
                 .append(bear ? " below the signal day's low " : " above the signal day's high ")
                 .append(PriceRules.f2(bear ? s.low() : s.high())).append(" (signal on ").append(s.date()).append(").");
        }
        return Optional.of(new PriceRule.Hit(hit.get().text() + extra, ev));
    }

    /**
     * Refinements and severity overrides from config: {@code price.refine.<ruleId>.minRelVolume}, {@code .priorReturnDays},
     * {@code .priorReturnPct}, {@code .trendFilter}, {@code .confirm}, and {@code price.severity.<ruleId>} (info|watch|warning).
     */
    public static Map<String, Refinement> fromMap(Map<String, String> m, List<PriceRule> rules) {
        Map<String, Refinement> out = new LinkedHashMap<>();
        for (PriceRule r : rules) {
            String p = "price.refine." + r.id() + ".";
            Double rv = dbl(m.get(p + "minRelVolume"));
            Double days = dbl(m.get(p + "priorReturnDays"));
            Double pct = dbl(m.get(p + "priorReturnPct"));
            boolean trend = Boolean.parseBoolean(m.getOrDefault(p + "trendFilter", "false").strip());
            boolean conf = Boolean.parseBoolean(m.getOrDefault(p + "confirm", "false").strip());
            String sev = m.get("price.severity." + r.id());
            Refinement f = new Refinement(rv, days == null ? null : days.intValue(), pct, trend, conf,
                    sev == null ? null : Severity.valueOf(sev.strip().toUpperCase(Locale.ROOT)));
            if (!f.isNone() || f.severity() != null) {
                out.put(r.id(), f);
            }
        }
        return out;
    }

    /** The rules with every configured refinement applied. */
    public static List<PriceRule> applyAll(List<PriceRule> rules, Map<String, Refinement> refinements) {
        return rules.stream().map(r -> refinements.getOrDefault(r.id(), NONE).apply(r)).toList();
    }

    private static Double dbl(String s) {
        return s == null || s.isBlank() ? null : Double.valueOf(s.strip());
    }
}
