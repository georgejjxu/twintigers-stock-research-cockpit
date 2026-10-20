package ca.twintigers.research.domain.portfolio;

import ca.twintigers.research.domain.market.CorporateAction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Portfolio returns from the transaction history and daily closes, in the base currency.
 * <ul>
 *   <li>Holdings are replayed day by day from the transactions. Quantities are converted to today's share units
 *       with the splits after each trade, because the closes are split-adjusted.</li>
 *   <li>Return: time-weighted, linked daily. Buys count at the start of their day at the trade price (incl. fee),
 *       sells at the end of their day (net of fee), so a day's return is
 *       (value − previous value − bought + sold [+ dividends]) / (previous value + bought).</li>
 *   <li>Two returns: price return (excluding dividends; comparable with Ghostfolio's net performance) and total
 *       return (dividends counted on their payment day).</li>
 *   <li>Cash: as in Ghostfolio, cash in the base currency is not part of the return; cash in another currency is held
 *       like a position (its dated balances are the flows), so its exchange-rate moves count.</li>
 *   <li>Currencies: each trade converts at that day's rate; holdings value at each day's rate (so currency moves are
 *       part of the return, as in Ghostfolio's "with currency effect").</li>
 * </ul>
 */
public final class PerformanceCalculator {

    public static final String METHOD = "time-weighted, excl. dividends (Java ledger)";

    private PerformanceCalculator() {
    }

    /** Daily closes and exchange rates, looked up on or before a day. */
    public interface Prices {
        /** Close on or before {@code day} in the listing's currency; NaN when there is none. */
        double close(String symbol, LocalDate day);

        /** Base-currency units per unit of {@code currency} on or before {@code day} (1 for the base); NaN when unknown. */
        double fx(String currency, LocalDate day);
    }

    /**
     * @param from           first day of the range (its base day is the last trading day before it)
     * @param to             last valuation day in the range
     * @param valueStart     holdings value on the base day (0 if nothing was held)
     * @param bought         cost of buys in the range, fees included
     * @param sold           proceeds of sells in the range, net of fees
     * @param gain           valueEnd − valueStart − bought + sold (price and currency gain, after fees)
     * @param costBasis      average cost of the holdings at the end (trade-day exchange rates)
     * @param priceReturnPct time-weighted return in %, excluding dividends
     * @param totalReturnPct time-weighted return in %, including dividends
     */
    public record Report(String range, LocalDate from, LocalDate to, BigDecimal valueStart, BigDecimal valueEnd,
                         BigDecimal bought, BigDecimal sold, BigDecimal dividends, BigDecimal fees, BigDecimal gain,
                         BigDecimal costBasis, BigDecimal priceReturnPct, BigDecimal totalReturnPct, List<String> notes,
                         List<Point> points) {

        public Performance toPerformance() {
            return new Performance(range, valueEnd, costBasis, gain, priceReturnPct, METHOD);
        }
    }

    /**
     * One valuation day in the range: holdings value, money put in so far within the range (start value + bought − sold),
     * and the time-weighted price return from the start of the range to that day, in %.
     */
    public record Point(LocalDate date, BigDecimal value, BigDecimal netInvested, BigDecimal returnPct) {
    }

    /** New shares per share held on {@code day}: the product of the symbol's splits after that day. */
    public static double splitFactor(String symbol, LocalDate day, List<CorporateAction> actions) {
        double f = 1;
        for (CorporateAction a : actions) {
            if (a.isSplit() && a.symbol().equals(symbol) && a.date().isAfter(day)) {
                f *= a.splitRatio();
            }
        }
        return f;
    }

    /** First and last day of a range: 1d, wtd, mtd, ytd, 1y, 5y, max, or a year such as 2025. */
    public static LocalDate[] period(String range, LocalDate today, LocalDate firstActivity) {
        String r = range == null ? "max" : range.strip().toLowerCase(Locale.ROOT);
        return switch (r) {
            case "1d" -> new LocalDate[] {today, today};
            case "wtd" -> new LocalDate[] {today.with(DayOfWeek.MONDAY), today};
            case "mtd" -> new LocalDate[] {today.withDayOfMonth(1), today};
            case "ytd" -> new LocalDate[] {today.withDayOfYear(1), today};
            case "1y" -> new LocalDate[] {today.minusYears(1).plusDays(1), today};
            case "5y" -> new LocalDate[] {today.minusYears(5).plusDays(1), today};
            case "max" -> new LocalDate[] {firstActivity, today};
            default -> {
                if (!r.matches("\\d{4}")) {
                    throw new IllegalArgumentException("Range must be 1d, wtd, mtd, ytd, 1y, 5y, max or a year, not " + range + ".");
                }
                int y = Integer.parseInt(r);
                LocalDate end = LocalDate.of(y, 12, 31);
                yield new LocalDate[] {LocalDate.of(y, 1, 1), end.isAfter(today) ? today : end};
            }
        };
    }

    /**
     * @param valuationDays trading days with prices, ascending (at least from the first transaction to today)
     * @param splits        stock splits per symbol (other corporate actions are ignored here)
     */
    public static Report compute(String range, List<Activity> activities, Map<String, List<CorporateAction>> splits, Prices prices,
                                 List<LocalDate> valuationDays, LocalDate today) {
        return compute(range, activities, splits, List.of(), null, prices, valuationDays, today);
    }

    /**
     * @param cash          dated cash balances; those in a currency other than {@code baseCurrency} are held like a
     *                      position (a higher balance is money put in at that day's rate, a lower one money taken out),
     *                      so their exchange-rate moves are part of the return, as in Ghostfolio
     * @param baseCurrency  the portfolio's currency (its cash is left out)
     */
    public static Report compute(String range, List<Activity> activities, Map<String, List<CorporateAction>> splits,
                                 List<CashBalance> cash, String baseCurrency, Prices prices, List<LocalDate> valuationDays,
                                 LocalDate today) {
        List<CashBalance> foreignCash = cash.stream().filter(c -> c.currency() != null && !c.currency().equalsIgnoreCase(baseCurrency)
                && !c.date().isAfter(today)).sorted(Comparator.comparing(CashBalance::date)).toList();
        List<Activity> acts = new ArrayList<>(activities.stream().filter(a -> a.date() != null && !a.date().isAfter(today)).toList());
        acts.sort(Comparator.comparing(Activity::date));
        Set<String> notes = new LinkedHashSet<>();
        List<LocalDate> days = valuationDays.stream().filter(d -> !d.isAfter(today)).sorted().toList();
        if (acts.isEmpty() || days.isEmpty()) {
            notes.add(acts.isEmpty() ? "No transactions recorded." : "No prices available.");
            return empty(range, today, notes);
        }
        LocalDate first = foreignCash.isEmpty() || acts.getFirst().date().isBefore(foreignCash.getFirst().date())
                ? acts.getFirst().date() : foreignCash.getFirst().date();
        LocalDate[] p = period(range, today, first);
        LocalDate endDay = lastOnOrBefore(days, p[1]);
        if (endDay == null) {
            notes.add("No prices on or before " + p[1] + ".");
            return empty(range, today, notes);
        }
        LocalDate from = p[0];
        LocalDate baseDay;
        if ("1d".equalsIgnoreCase(range.strip())) {
            baseDay = lastBefore(days, endDay);
            from = endDay;
        } else {
            baseDay = lastBefore(days, from);
        }

        Map<String, Double> qty = new HashMap<>();
        Map<String, Double> cost = new HashMap<>();
        Map<String, String> currency = new HashMap<>();
        Map<String, Double> lastTrade = new HashMap<>();
        double vPrev = 0;
        double vStart = 0;
        double bought = 0;
        double sold = 0;
        double dividends = 0;
        double fees = 0;
        double twrPrice = 1;
        double twrTotal = 1;
        int next = 0;
        int nextCash = 0;
        List<Point> points = new ArrayList<>();
        Map<String, Double> cashHeld = new HashMap<>();
        Map<String, String> cashCurrency = new HashMap<>();
        for (LocalDate d : days) {
            if (d.isAfter(endDay)) {
                break;
            }
            boolean inRange = baseDay == null || d.isAfter(baseDay);
            double buysToday = 0;
            double sellsToday = 0;
            double divToday = 0;
            while (next < acts.size() && !acts.get(next).date().isAfter(d)) {
                Activity a = acts.get(next++);
                String s = a.symbol();
                String type = a.type() == null ? "" : a.type().toUpperCase(Locale.ROOT);
                double rate = rate(prices, a.currency(), a.date(), notes);
                double q = a.quantity().doubleValue();
                double price = a.unitPrice().doubleValue();
                double fee = a.fee() == null ? 0 : a.fee().doubleValue();
                double f = splitFactor(s, a.date(), splits.getOrDefault(s, List.of()));
                if (inRange) {
                    fees += fee * rate;
                }
                switch (type) {
                    case "BUY" -> {
                        double c = (q * price + fee) * rate;
                        qty.merge(s, q * f, Double::sum);
                        cost.merge(s, c, Double::sum);
                        currency.putIfAbsent(s, a.currency());
                        lastTrade.put(s, price / f);
                        buysToday += c;
                    }
                    case "SELL" -> {
                        double held = qty.getOrDefault(s, 0.0);
                        double sq = Math.min(q * f, held);
                        if (held > 0) {
                            cost.put(s, cost.getOrDefault(s, 0.0) * (1 - sq / held));
                        }
                        if (q * f > held + 1e-9) {
                            notes.add("Sell of " + a.quantity().toPlainString() + " " + s + " on " + a.date() + " is more than was held.");
                        }
                        qty.put(s, held - sq);
                        lastTrade.put(s, price / f);
                        sellsToday += (q * price - fee) * rate;
                    }
                    case "DIVIDEND" -> divToday += (q * price - fee) * rate;
                    default -> notes.add("Transactions of type " + type + " are not counted.");
                }
            }
            while (nextCash < foreignCash.size() && !foreignCash.get(nextCash).date().isAfter(d)) {
                CashBalance c = foreignCash.get(nextCash++);
                String key = "cash:" + c.account();
                double held = cashHeld.getOrDefault(key, 0.0);
                double delta = c.balance().doubleValue() - held;
                double rate = rate(prices, c.currency(), c.date(), notes);
                if (delta > 0) {
                    buysToday += delta * rate;
                    cost.merge(key, delta * rate, Double::sum);
                } else if (delta < 0) {
                    sellsToday += -delta * rate;
                    cost.put(key, held > 0 ? cost.getOrDefault(key, 0.0) * (1 + delta / held) : 0.0);
                }
                cashHeld.put(key, c.balance().doubleValue());
                cashCurrency.put(key, c.currency());
            }
            double v = 0;
            for (var e : cashHeld.entrySet()) {
                v += e.getValue() * rate(prices, cashCurrency.get(e.getKey()), d, notes);
            }
            for (var e : qty.entrySet()) {
                if (e.getValue() <= 1e-12) {
                    continue;
                }
                String s = e.getKey();
                double close = prices.close(s, d);
                if (Double.isNaN(close)) {
                    close = lastTrade.getOrDefault(s, 0.0);
                    notes.add("No price for " + s + " on or before " + d + "; the last trade price was used.");
                }
                v += e.getValue() * close * rate(prices, currency.get(s), d, notes);
            }
            if (inRange) {
                double den = vPrev + buysToday;
                if (den > 1e-9) {
                    double num = v - vPrev - buysToday + sellsToday;
                    twrPrice *= 1 + num / den;
                    twrTotal *= 1 + (num + divToday) / den;
                } else if (divToday > 0) {
                    notes.add("A dividend on " + d + " arrived when nothing was held; it is in the dividend total but not in the return.");
                }
                bought += buysToday;
                sold += sellsToday;
                dividends += divToday;
                points.add(new Point(d, money(v), money(vStart + bought - sold), pct(twrPrice)));
            } else {
                vStart = v;
            }
            vPrev = v;
        }
        double costBasis = cost.entrySet().stream().filter(e -> qty.getOrDefault(e.getKey(), 0.0) > 1e-12
                || cashHeld.getOrDefault(e.getKey(), 0.0) > 1e-12).mapToDouble(Map.Entry::getValue).sum();
        return new Report(range, from, endDay, money(vStart), money(vPrev), money(bought), money(sold), money(dividends), money(fees),
                money(vPrev - vStart - bought + sold), money(costBasis), pct(twrPrice), pct(twrTotal), List.copyOf(notes),
                List.copyOf(points));
    }

    private static double rate(Prices prices, String currency, LocalDate day, Set<String> notes) {
        double r = prices.fx(currency, day);
        if (Double.isNaN(r)) {
            notes.add("No exchange rate for " + currency + " on or before " + day + "; counted at 1.");
            return 1;
        }
        return r;
    }

    private static Report empty(String range, LocalDate today, Set<String> notes) {
        return new Report(range, today, today, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, List.copyOf(notes), List.of());
    }

    static LocalDate lastOnOrBefore(List<LocalDate> days, LocalDate d) {
        LocalDate out = null;
        for (LocalDate x : days) {
            if (x.isAfter(d)) {
                break;
            }
            out = x;
        }
        return out;
    }

    static LocalDate lastBefore(List<LocalDate> days, LocalDate d) {
        return lastOnOrBefore(days, d.minusDays(1));
    }

    static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    static BigDecimal pct(double growth) {
        return BigDecimal.valueOf((growth - 1) * 100).setScale(4, RoundingMode.HALF_EVEN);
    }
}
