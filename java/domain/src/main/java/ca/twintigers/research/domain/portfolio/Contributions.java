package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Money put into holdings per month: buys (fees included) minus sells (after fees), in the base currency. */
public final class Contributions {

    private Contributions() {
    }

    public record Month(YearMonth month, BigDecimal bought, BigDecimal sold, BigDecimal net) {
    }

    public static List<Month> byMonth(List<Activity> activities, PerformanceCalculator.Prices prices) {
        Map<YearMonth, double[]> m = new TreeMap<>();
        for (Activity a : activities) {
            String type = a.type() == null ? "" : a.type().toUpperCase(Locale.ROOT);
            if (!type.equals("BUY") && !type.equals("SELL")) {
                continue;
            }
            double rate = prices.fx(a.currency(), a.date());
            rate = Double.isNaN(rate) ? 1 : rate;
            double fee = a.fee() == null ? 0 : a.fee().doubleValue();
            double gross = a.quantity().doubleValue() * a.unitPrice().doubleValue();
            double[] v = m.computeIfAbsent(YearMonth.from(a.date()), k -> new double[2]);
            if (type.equals("BUY")) {
                v[0] += (gross + fee) * rate;
            } else {
                v[1] += (gross - fee) * rate;
            }
        }
        List<Month> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Month(k, scale(v[0]), scale(v[1]), scale(v[0] - v[1]))));
        return out;
    }

    static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }
}
