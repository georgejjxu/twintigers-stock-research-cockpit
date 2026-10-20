package ca.twintigers.research.domain.portfolio;

import ca.twintigers.research.domain.market.CorporateAction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Dividend income from the recorded transactions, and dividends the company paid that were not recorded. */
public final class Dividends {

    private Dividends() {
    }

    /** Income in one month, in the base currency (each dividend at its day's exchange rate, net of withholding/fee). */
    public record Month(YearMonth month, BigDecimal amountInBase, int payments) {
    }

    /**
     * A dividend the market data shows for a holding, with no DIVIDEND transaction recorded near it.
     *
     * @param exDate    shares held at the close before this day receive it; the payment usually follows within weeks
     * @param shares    shares held in the account before the ex-date, in today's share units (after later splits)
     * @param perShare  amount per share in {@code currency}
     */
    public record Missing(String account, String symbol, LocalDate exDate, BigDecimal shares, BigDecimal perShare, String currency) {
        public BigDecimal amount() {
            return shares.multiply(perShare).setScale(2, RoundingMode.HALF_EVEN);
        }
    }

    /** Recorded dividend income by month, oldest first. */
    public static List<Month> byMonth(List<Activity> activities, PerformanceCalculator.Prices prices) {
        Map<YearMonth, BigDecimal> sum = new TreeMap<>();
        Map<YearMonth, Integer> count = new TreeMap<>();
        for (Activity a : activities) {
            if (!a.isDividend()) {
                continue;
            }
            double rate = prices.fx(a.currency(), a.date());
            double net = a.quantity().doubleValue() * a.unitPrice().doubleValue() - (a.fee() == null ? 0 : a.fee().doubleValue());
            YearMonth m = YearMonth.from(a.date());
            sum.merge(m, BigDecimal.valueOf(net * (Double.isNaN(rate) ? 1 : rate)), BigDecimal::add);
            count.merge(m, 1, Integer::sum);
        }
        List<Month> out = new ArrayList<>();
        sum.forEach((m, v) -> out.add(new Month(m, v.setScale(2, RoundingMode.HALF_EVEN), count.get(m))));
        return out;
    }

    /**
     * Dividends (ex-dates from {@code actions}) on shares held in an account, with no DIVIDEND transaction for that account
     * and symbol from 7 days before to 75 days after the ex-date. Newest first. Suggestions only: the user records them.
     */
    public static List<Missing> missing(List<Activity> activities, List<CorporateAction> actions, LocalDate today) {
        List<Missing> out = new ArrayList<>();
        List<Activity> trades = activities.stream()
                .filter(a -> "BUY".equalsIgnoreCase(a.type()) || "SELL".equalsIgnoreCase(a.type())).toList();
        for (CorporateAction d : actions) {
            if (d.isSplit() || d.dividendPerShare() <= 0 || d.date().isAfter(today)) {
                continue;
            }
            Map<String, Double> held = new TreeMap<>();
            String currency = null;
            for (Activity a : trades) {
                if (!a.symbol().equals(d.symbol()) || !a.date().isBefore(d.date())) {
                    continue;
                }
                double f = PerformanceCalculator.splitFactor(a.symbol(), a.date(), actions);
                double q = a.quantity().doubleValue() * f * ("BUY".equalsIgnoreCase(a.type()) ? 1 : -1);
                held.merge(a.account(), q, Double::sum);
                currency = a.currency();
            }
            for (var e : held.entrySet()) {
                if (e.getValue() <= 1e-9) {
                    continue;
                }
                boolean recorded = activities.stream().anyMatch(a -> a.isDividend() && a.symbol().equals(d.symbol())
                        && e.getKey().equals(a.account()) && !a.date().isBefore(d.date().minusDays(7))
                        && !a.date().isAfter(d.date().plusDays(75)));
                if (!recorded) {
                    // today's share units: the data source's per-share amounts are split-adjusted like its closes
                    out.add(new Missing(e.getKey(), d.symbol(), d.date(), BigDecimal.valueOf(e.getValue()).stripTrailingZeros(),
                            BigDecimal.valueOf(d.dividendPerShare()), currency));
                }
            }
        }
        out.sort(Comparator.comparing(Missing::exDate).reversed().thenComparing(Missing::symbol).thenComparing(Missing::account));
        return out;
    }
}
