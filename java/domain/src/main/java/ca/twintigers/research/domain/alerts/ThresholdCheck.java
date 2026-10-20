package ca.twintigers.research.domain.alerts;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Indicators;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluates one threshold alert against the latest price (and daily bars, for the trailing stop). */
public final class ThresholdCheck {

    private ThresholdCheck() {
    }

    /**
     * @param condition whether the alert's condition holds now
     * @param evidence  the numbers used, for the notification and the audit trail
     */
    public record Result(boolean condition, String text, Map<String, Object> evidence) {
    }

    /**
     * @param price     latest price (delayed quote during the day, the close after it)
     * @param priceTime when that price was observed, for the message
     * @param bars      daily bars up to the latest completed day (needed for TRAILING_ATR only)
     */
    public static Result evaluate(Alert a, double price, String priceTime, List<Bar> bars) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("price", round(price, 4));
        ev.put("price_time", priceTime);
        return switch (a.kind()) {
            case ABOVE -> {
                ev.put("level", a.level());
                yield new Result(price >= a.level(), a.symbol() + " at " + f2(price) + " is at or above your level "
                        + f2(a.level()) + ".", ev);
            }
            case BELOW -> {
                ev.put("level", a.level());
                yield new Result(price <= a.level(), a.symbol() + " at " + f2(price) + " is at or below your level "
                        + f2(a.level()) + ".", ev);
            }
            case MOVE_PCT -> {
                double move = (price / a.reference() - 1) * 100;
                ev.put("reference", a.reference());
                ev.put("move_pct", round(move, 2));
                ev.put("threshold_pct", a.pct());
                boolean hit = a.pct() >= 0 ? move >= a.pct() : move <= a.pct();
                yield new Result(hit, a.symbol() + " moved " + (move >= 0 ? "+" : "") + f2(move) + "% from your reference "
                        + f2(a.reference()) + " (alert at " + (a.pct() >= 0 ? "+" : "") + f2(a.pct()) + "%), now " + f2(price) + ".", ev);
            }
            case TRAILING_ATR -> trailing(a, price, bars, ev);
        };
    }

    private static Result trailing(Alert a, double price, List<Bar> bars, Map<String, Object> ev) {
        double[] atr = Indicators.atr(bars, 14);
        int last = bars.size() - 1;
        if (last < 14 || Double.isNaN(atr[last])) {
            ev.put("note", "not enough history for ATR(14)");
            return new Result(false, a.symbol() + ": not enough daily history for the trailing stop yet.", ev);
        }
        LocalDate since = a.since() != null ? a.since() : bars.getFirst().date();
        double highest = Double.NEGATIVE_INFINITY;
        LocalDate highDate = null;
        for (Bar b : bars) {
            if (!b.date().isBefore(since) && b.close() > highest) {
                highest = b.close();
                highDate = b.date();
            }
        }
        double stop = highest - a.atrMultiple() * atr[last];
        ev.put("highest_close", round(highest, 4));
        ev.put("highest_close_date", String.valueOf(highDate));
        ev.put("atr14", round(atr[last], 4));
        ev.put("atr_multiple", a.atrMultiple());
        ev.put("stop", round(stop, 4));
        return new Result(price < stop, a.symbol() + " at " + f2(price) + " is below its trailing stop " + f2(stop)
                + " (highest close " + f2(highest) + " on " + highDate + " − " + f2(a.atrMultiple()) + " × ATR14 " + f2(atr[last]) + ").", ev);
    }

    static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static double round(double v, int places) {
        return java.math.BigDecimal.valueOf(v).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }
}
