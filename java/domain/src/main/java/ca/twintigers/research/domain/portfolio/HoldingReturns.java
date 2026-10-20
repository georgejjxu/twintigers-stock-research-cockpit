package ca.twintigers.research.domain.portfolio;

import ca.twintigers.research.domain.market.CorporateAction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profit and loss per holding from its transactions (average-cost method, split-adjusted), valued at the latest
 * close. Amounts in the base currency use each trade's exchange rate for costs and today's for the value, so a
 * USD holding's gain includes the currency move, as Ghostfolio shows it "with currency effect".
 */
public final class HoldingReturns {

    private HoldingReturns() {
    }

    /**
     * @param avgCost       average cost per share in the listing currency, after splits and including fees
     * @param costBasis     what the shares held cost, in the base currency
     * @param unrealized    value − cost basis
     * @param realized      gains on shares sold (proceeds after fees − their average cost), in the base currency
     * @param dividends     dividends received, net of withholding, in the base currency
     * @param totalReturn   unrealized + realized + dividends
     * @param totalReturnPct total return / everything paid for the shares ever bought (fees included)
     */
    public record Holding(String symbol, String currency, BigDecimal quantity, BigDecimal avgCost, BigDecimal lastPrice,
                          BigDecimal costBasis, BigDecimal value, BigDecimal unrealized, BigDecimal unrealizedPct, BigDecimal realized,
                          BigDecimal dividends, BigDecimal fees, BigDecimal totalReturn, BigDecimal totalReturnPct, LocalDate firstBuy,
                          int transactions) {
    }

    public static List<Holding> compute(List<Activity> activities, Map<String, List<CorporateAction>> splits,
                                        PerformanceCalculator.Prices prices, LocalDate today) {
        List<Activity> acts = new ArrayList<>(activities.stream().filter(a -> a.date() != null && !a.date().isAfter(today)).toList());
        acts.sort(Comparator.comparing(Activity::date));
        Map<String, double[]> s = new LinkedHashMap<>(); // qty, costBase, costLocal, realized, dividends, fees, bought
        Map<String, String> currency = new LinkedHashMap<>();
        Map<String, LocalDate> first = new LinkedHashMap<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Activity a : acts) {
            String sym = a.symbol();
            String type = a.type() == null ? "" : a.type().toUpperCase(Locale.ROOT);
            double[] v = s.computeIfAbsent(sym, k -> new double[7]);
            currency.putIfAbsent(sym, a.currency());
            count.merge(sym, 1, Integer::sum);
            double rate = rate(prices, a.currency(), a.date());
            double f = PerformanceCalculator.splitFactor(sym, a.date(), splits.getOrDefault(sym, List.of()));
            double q = a.quantity().doubleValue();
            double p = a.unitPrice().doubleValue();
            double fee = a.fee() == null ? 0 : a.fee().doubleValue();
            v[5] += fee * rate;
            switch (type) {
                case "BUY" -> {
                    v[0] += q * f;
                    v[1] += (q * p + fee) * rate;
                    v[2] += q * p + fee;
                    v[6] += (q * p + fee) * rate;
                    first.putIfAbsent(sym, a.date());
                }
                case "SELL" -> {
                    double held = v[0];
                    double sold = Math.min(q * f, held);
                    double share = held > 0 ? sold / held : 0;
                    double costOut = v[1] * share;
                    v[3] += (q * p - fee) * rate - costOut;
                    v[1] -= costOut;
                    v[2] -= v[2] * share;
                    v[0] = held - sold;
                }
                case "DIVIDEND" -> v[4] += (q * p - fee) * rate;
                default -> {
                    // other types do not change a holding
                }
            }
        }
        List<Holding> out = new ArrayList<>();
        s.forEach((sym, v) -> {
            String cur = currency.get(sym);
            double qty = v[0];
            double close = prices.close(sym, today);
            double value = qty > 1e-12 && !Double.isNaN(close) ? qty * close * rate(prices, cur, today) : 0;
            double unrealized = qty > 1e-12 ? value - v[1] : 0;
            double total = unrealized + v[3] + v[4];
            out.add(new Holding(sym, cur, BigDecimal.valueOf(qty).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros(),
                    qty > 1e-12 ? scale(v[2] / qty, 4) : null, Double.isNaN(close) ? null : scale(close, 4),
                    scale(v[1], 2), scale(value, 2), scale(unrealized, 2), qty > 1e-12 && v[1] > 0 ? scale(unrealized / v[1] * 100, 2) : null,
                    scale(v[3], 2), scale(v[4], 2), scale(v[5], 2), scale(total, 2), v[6] > 0 ? scale(total / v[6] * 100, 2) : null,
                    first.get(sym), count.get(sym)));
        });
        out.sort(Comparator.comparing(Holding::value).reversed());
        return out;
    }

    private static double rate(PerformanceCalculator.Prices prices, String currency, LocalDate day) {
        double r = prices.fx(currency, day);
        return Double.isNaN(r) ? 1 : r;
    }

    static BigDecimal scale(double v, int digits) {
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_EVEN);
    }
}
