package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.CorporateAction;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.CashBalance;
import ca.twintigers.research.domain.portfolio.Dividends;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.PerformanceCalculator;
import ca.twintigers.research.ports.CorporateActions;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Returns and dividend income computed from a source's transactions with daily closes (MarketData) and splits
 * (CorporateActions), so they do not depend on the holdings source. A source that computes returns itself
 * (Ghostfolio declares PERFORMANCE) is asked directly by {@link #performance(String)}; {@link #report(String)} always
 * computes, which is what the Ghostfolio parity check compares against.
 */
public final class PerformanceService {

    private final PortfolioSource source;
    private final MarketData market;
    private final CorporateActions actions;
    private final String base;
    private final Clock clock;

    public PerformanceService(PortfolioSource source, MarketData market, CorporateActions actions, String baseCurrency, Clock clock) {
        this.source = source;
        this.market = market;
        this.actions = actions;
        this.base = baseCurrency;
        this.clock = clock;
    }

    /** The source's own figure when it has one, else the computed one; empty without transactions. */
    public Optional<Performance> performance(String range) throws DataSourceException {
        if (source.capabilities().contains(PortfolioSource.Capability.PERFORMANCE)) {
            return source.performance(range);
        }
        PerformanceCalculator.Report r = report(range);
        return r.priceReturnPct() == null ? Optional.empty() : Optional.of(r.toPerformance());
    }

    /** Computed from the source's transactions. */
    public PerformanceCalculator.Report report(String range) throws DataSourceException {
        return report(range, source.activities(), source.cashHistory());
    }

    /** Several ranges at once, loading prices once (the Performance page). */
    public Map<String, PerformanceCalculator.Report> reports(List<String> ranges) throws DataSourceException {
        List<Activity> activities = source.activities();
        List<CashBalance> cash = source.cashHistory();
        LocalDate today = LocalDate.now(clock);
        Market m = new Market(activities, cash, today);
        Map<String, PerformanceCalculator.Report> out = new LinkedHashMap<>();
        for (String r : ranges) {
            out.put(r, PerformanceCalculator.compute(r, activities, m.splits, cash, base, m, m.days, today));
        }
        return out;
    }

    /** Years with transactions, oldest first (for per-year returns). */
    public List<String> years() throws DataSourceException {
        return source.activities().stream().map(a -> a.date().getYear()).distinct().sorted()
                .map(String::valueOf).toList();
    }

    public PerformanceCalculator.Report report(String range, List<Activity> activities, List<CashBalance> cash) throws DataSourceException {
        LocalDate today = LocalDate.now(clock);
        Market m = new Market(activities, cash, today);
        return PerformanceCalculator.compute(range, activities, m.splits, cash, base, m, m.days, today);
    }

    public List<Dividends.Month> dividendIncome() throws DataSourceException {
        List<Activity> acts = source.activities();
        return Dividends.byMonth(acts, new Market(acts.stream().filter(Activity::isDividend).toList(), List.of(), LocalDate.now(clock)));
    }

    /** Profit and loss per holding (average cost, split-adjusted), valued at the latest close. */
    public List<ca.twintigers.research.domain.portfolio.HoldingReturns.Holding> holdings() throws DataSourceException {
        List<Activity> acts = source.activities();
        LocalDate today = LocalDate.now(clock);
        Market m = new Market(acts, List.of(), today);
        return ca.twintigers.research.domain.portfolio.HoldingReturns.compute(acts, m.splits, m, today);
    }

    /** Money put into holdings per month (buys minus sells), in the base currency. */
    public List<ca.twintigers.research.domain.portfolio.Contributions.Month> contributions() throws DataSourceException {
        List<Activity> acts = source.activities();
        return ca.twintigers.research.domain.portfolio.Contributions.byMonth(acts, new Market(acts, List.of(), LocalDate.now(clock)));
    }

    /** A benchmark's change in % from the last close before {@code from}, per day up to {@code to} (local currency). */
    public List<PerformanceCalculator.Point> benchmark(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
        List<Bar> bars = market.dailyBars(symbol, from.minusDays(10), to);
        Double base = null;
        List<PerformanceCalculator.Point> out = new ArrayList<>();
        for (Bar b : bars) {
            if (b.date().isBefore(from)) {
                base = b.close();
            } else if (base != null) {
                out.add(new PerformanceCalculator.Point(b.date(), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.valueOf((b.close() / base - 1) * 100).setScale(4, java.math.RoundingMode.HALF_EVEN)));
            } else {
                base = b.close(); // range starts before the benchmark's history: start from its first close
            }
        }
        return out;
    }

    /** Dividends paid on holdings (per the market data) with no DIVIDEND transaction recorded; suggestions only. */
    public List<Dividends.Missing> missingDividends() throws DataSourceException {
        List<Activity> acts = source.activities();
        LocalDate today = LocalDate.now(clock);
        List<CorporateAction> all = new ArrayList<>();
        for (String s : acts.stream().map(Activity::symbol).distinct().toList()) {
            LocalDate first = acts.stream().filter(a -> a.symbol().equals(s)).map(Activity::date).min(LocalDate::compareTo).orElse(today);
            try {
                all.addAll(actions.corporateActions(s, first, today));
            } catch (DataSourceException e) {
                // no suggestions for this symbol
            }
        }
        return Dividends.missing(acts, all, today);
    }

    /** Daily closes, exchange rates and splits for the symbols in {@code activities}, looked up on or before a day. */
    final class Market implements PerformanceCalculator.Prices {
        final Map<String, TreeMap<LocalDate, Double>> closes = new HashMap<>();
        final Map<String, TreeMap<LocalDate, Double>> fx = new HashMap<>();
        final Map<String, List<CorporateAction>> splits = new LinkedHashMap<>();
        final List<LocalDate> days;

        Market(List<Activity> activities, List<CashBalance> cash, LocalDate today) throws DataSourceException {
            LocalDate first = java.util.stream.Stream.concat(activities.stream().map(Activity::date), cash.stream().map(CashBalance::date))
                    .filter(d -> d != null).min(Comparator.naturalOrder()).orElse(today);
            LocalDate from = first.minusDays(10);
            TreeSet<LocalDate> d = new TreeSet<>();
            for (String s : activities.stream().map(Activity::symbol).distinct().toList()) {
                TreeMap<LocalDate, Double> c = series(s, from, today);
                closes.put(s, c);
                d.addAll(c.keySet().stream().filter(x -> !x.isBefore(first.minusDays(10))).toList());
                try {
                    splits.put(s, actions.corporateActions(s, first, today).stream().filter(CorporateAction::isSplit).toList());
                } catch (DataSourceException e) {
                    splits.put(s, List.of());
                }
            }
            for (String cur : java.util.stream.Stream.concat(activities.stream().map(Activity::currency),
                    cash.stream().map(CashBalance::currency)).distinct().toList()) {
                if (cur != null && !cur.equalsIgnoreCase(base)) {
                    fx.put(cur, series(cur + base + "=X", from, today));
                }
            }
            this.days = List.copyOf(d);
        }

        private TreeMap<LocalDate, Double> series(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
            TreeMap<LocalDate, Double> out = new TreeMap<>();
            for (Bar b : market.dailyBars(symbol, from, to)) {
                out.put(b.date(), b.close());
            }
            return out;
        }

        @Override
        public double close(String symbol, LocalDate day) {
            var e = closes.getOrDefault(symbol, new TreeMap<>()).floorEntry(day);
            return e == null ? Double.NaN : e.getValue();
        }

        @Override
        public double fx(String currency, LocalDate day) {
            if (currency == null || currency.equalsIgnoreCase(base)) {
                return 1;
            }
            TreeMap<LocalDate, Double> s = fx.get(currency);
            if (s == null || s.isEmpty()) {
                return Double.NaN;
            }
            var e = s.floorEntry(day);
            return (e == null ? s.firstEntry() : e).getValue();
        }
    }

}
