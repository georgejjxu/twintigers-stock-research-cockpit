package ca.twintigers.research.web;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the chart shows when a signal row is clicked: the bars the rule looked at, the price levels it compared
 * against, and its numbers in plain words. Everything comes from the signal's own evidence; nothing is recomputed.
 */
final class SignalExplainer {

    private SignalExplainer() {
    }

    /** Plain-English labels for evidence keys; unknown keys are shown with underscores as spaces. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("prev_close", "Previous close"), Map.entry("prev_open", "Previous open"),
            Map.entry("prev_high", "Previous high"), Map.entry("prev_low", "Previous low"),
            Map.entry("open", "Open"), Map.entry("high", "High"), Map.entry("low", "Low"), Map.entry("close", "Close"),
            Map.entry("volume", "Volume"), Map.entry("avg_volume", "Average volume (20 days before)"),
            Map.entry("rel_volume", "Volume vs average (×)"), Map.entry("change_pct", "Change on the day (%)"),
            Map.entry("open_to_close_pct", "Open to close (%)"), Map.entry("true_range", "True range"),
            Map.entry("atr_prev", "Average true range, day before (ATR 14)"), Map.entry("atr", "Average true range (ATR 14)"),
            Map.entry("range_atr_multiple", "Range in ATRs (×)"), Map.entry("close_location", "Close location in the day's range (0 = low, 1 = high)"),
            Map.entry("gap_pct", "Gap (%)"), Map.entry("days", "Days"), Map.entry("pattern", "Pattern"),
            Map.entry("change_pct_over_days", "Change over those days (%)"), Map.entry("sma20", "20-day average"),
            Map.entry("avg_volume_window", "Average volume in the window"), Map.entry("avg_volume_before", "Average volume before"),
            Map.entry("volume_ratio", "Volume ratio (×)"), Map.entry("level", "Support / resistance level"),
            Map.entry("level_touches", "Times the level held before"), Map.entry("level_swing_dates", "Days it held"),
            Map.entry("buffer", "Buffer below/above the level"), Map.entry("body", "Body (open to close)"),
            Map.entry("prev_body", "Previous body"), Map.entry("trend_context", "Trend before"),
            Map.entry("upper_shadow", "Upper shadow"), Map.entry("lower_shadow", "Lower shadow"), Map.entry("range", "Day's range"),
            Map.entry("day1_open", "Day 1 open"), Map.entry("day1_close", "Day 1 close"), Map.entry("day2_open", "Day 2 open"),
            Map.entry("day2_close", "Day 2 close"), Map.entry("day1_body_mid", "Day 1 body midpoint"),
            Map.entry("prev_body_mid", "Previous body midpoint"), Map.entry("close_before", "Close 3 days before"),
            Map.entry("sma20_before", "20-day average 3 days before"), Map.entry("change_pct_3_days", "Change over 3 days (%)"),
            Map.entry("rsi", "RSI (14)"), Map.entry("rsi_prev", "RSI the day before"), Map.entry("threshold", "Threshold"),
            Map.entry("macd", "MACD line"), Map.entry("signal", "Signal line"), Map.entry("histogram", "MACD − signal"),
            Map.entry("refine_rel_volume", "Refinement: volume vs average (×)"), Map.entry("refine_sma200", "Refinement: 200-day average"),
            Map.entry("refine_prior_move_pct", "Refinement: prior move (%)"), Map.entry("refine_prior_days", "Refinement: prior days"),
            Map.entry("refine_signal_date", "Refinement: pattern day"), Map.entry("refine_confirm_close", "Refinement: confirming close"),
            Map.entry("refine_signal_low", "Refinement: pattern day's low"), Map.entry("refine_signal_high", "Refinement: pattern day's high"));

    /** Evidence keys drawn as horizontal price levels, with their line titles. */
    static final Map<String, String> LEVELS = new LinkedHashMap<>(Map.of(
            "prev_close", "previous close", "prev_high", "previous high", "prev_low", "previous low",
            "level", "support / resistance", "day1_body_mid", "day 1 midpoint", "prev_body_mid", "previous midpoint",
            "refine_signal_low", "pattern low", "refine_signal_high", "pattern high", "refine_sma200", "200-day average"));

    /** How many bars, ending on the signal day, each pattern looks at (1 when not listed). */
    static int patternBars(String ruleId, Map<String, Object> evidence) {
        String id = ruleId.substring(0, ruleId.indexOf('_'));
        int n = switch (id) {
            case "PA03", "PA04", "K01", "K05", "K08" -> 2;
            case "K03", "K06", "K09" -> 3;
            case "PA05" -> evidence.get("days") instanceof Number d ? d.intValue() + 1 : 6;
            default -> 1;
        };
        return evidence.containsKey("refine_signal_date") ? n + 1 : n; // a confirmed signal adds the confirming day
    }

    static Map<String, Object> explain(Signal g, List<Bar> bars, String definition) {
        Map<String, Object> ev = g.evidence();
        int idx = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).date().equals(g.date())) {
                idx = i;
            }
        }
        List<String> days = new ArrayList<>();
        if (idx >= 0) {
            for (int i = Math.max(0, idx - patternBars(g.ruleId(), ev) + 1); i <= idx; i++) {
                days.add(bars.get(i).date().toString());
            }
        }
        if (ev.get("level_swing_dates") instanceof List<?> swings) { // where the broken level held before
            for (Object d : swings) {
                if (!days.contains(String.valueOf(d))) {
                    days.add(String.valueOf(d));
                }
            }
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        LEVELS.forEach((key, title) -> {
            if (ev.get(key) instanceof Number n) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("price", n.doubleValue());
                l.put("title", title);
                lines.add(l);
            }
        });
        if (ev.get("level") instanceof Number lvl && ev.get("buffer") instanceof Number buf) {
            Map<String, Object> l = new LinkedHashMap<>();
            boolean below = g.direction() == Signal.Direction.BEARISH;
            l.put("price", lvl.doubleValue() + (below ? -buf.doubleValue() : buf.doubleValue()));
            l.put("title", below ? "level − buffer" : "level + buffer");
            lines.add(l);
        }
        List<List<String>> facts = new ArrayList<>();
        ev.forEach((k, v) -> facts.add(List.of(LABELS.getOrDefault(k, k.replace('_', ' ')), format(v))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("lines", lines);
        out.put("facts", facts);
        out.put("definition", definition == null ? "" : definition);
        out.put("why", g.text());
        return out;
    }

    static String format(Object v) {
        if (v instanceof Long || v instanceof Integer) {
            return String.format(Locale.ROOT, "%,d", ((Number) v).longValue());
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) > 1000 ? String.format(Locale.ROOT, "%,.0f", d) : String.valueOf(d);
        }
        if (v instanceof List<?> l) {
            return String.join(", ", l.stream().map(String::valueOf).toList());
        }
        return String.valueOf(v);
    }

}
