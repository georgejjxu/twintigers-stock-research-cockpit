package ca.twintigers.research.application;

import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.fundamentals.HealthTable;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Indicators;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.domain.portfolio.WatchItem;
import ca.twintigers.research.domain.rules.Flag;
import ca.twintigers.research.domain.rules.Severity;
import ca.twintigers.research.ports.AlertStore;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.WatchlistStore;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Use cases behind the Stock Research Cockpit pages. Depends only on ports, so the pages work with any portfolio source.
 * Each view carries the numbers exactly as the tools compute them; the web layer only formats.
 */
public final class CockpitService {

    /** A fundamental audit result for one issuer (or why there is none). */
    public record Health(String secTicker, List<Flag> flags, HealthTable table, String unavailable) {
        public long count(Severity s) {
            return flags.stream().filter(f -> f.severity() == s).count();
        }
    }

    /** Recent price signals for one symbol (watch level and above), or why there are none. */
    public record Signals(String symbol, LocalDate asOf, List<Signal> signals, String unavailable) {
        public long count(Severity s) {
            return signals.stream().filter(g -> g.severity() == s).count();
        }
    }

    /** One range: the source's own figure (Ghostfolio) if it has one, and the Java calculation from the transactions. */
    public record ReturnRow(String range, String label, Optional<Performance> own, ca.twintigers.research.domain.portfolio.PerformanceCalculator.Report computed) {
    }

    public record PerformanceView(String source, String baseCurrency, List<ReturnRow> returns,
                                  List<ca.twintigers.research.domain.portfolio.Dividends.Month> dividends,
                                  List<ca.twintigers.research.domain.portfolio.Dividends.Missing> missing,
                                  List<ca.twintigers.research.domain.portfolio.Allocation.Slice> byHolding,
                                  List<ca.twintigers.research.domain.portfolio.Allocation.Slice> byCurrency,
                                  List<ca.twintigers.research.domain.portfolio.Allocation.Slice> byAccount, String error) {
    }

    public record PortfolioView(String source, Portfolio portfolio, List<Portfolio.IssuerWeight> issuers, List<Account> accounts,
                                Optional<Performance> ytd, Map<String, Health> healthByIssuer, Map<String, Signals> signalsBySymbol,
                                double concentrationLimitPct, String error) {
    }

    /**
     * @param dayChangePct close-to-close change of the latest bar
     * @param nearestAlert the active above/below alert level closest to the last close, if any
     */
    public record WatchRow(WatchItem item, boolean held, Bar last, Double dayChangePct, Double high52w, Double low52w,
                           Alert nearestAlert, Double distanceToAlertPct, Signals signals, Health health, String error) {
    }

    public record StockView(String symbol, boolean held, boolean watched, List<Bar> bars, double[] sma20, double[] atr,
                            Signals signals, List<Alert> alerts, Health health, String marketSource, String error) {
    }

    private final PortfolioSource portfolio;
    private final WatchlistStore watchlist;
    private final AlertStore alerts;
    private final MarketData market;
    private final PriceSignalService prices;
    private final FinancialHealthService health;
    private final Map<String, String> secTickers;
    private final Clock clock;
    private final Map<String, Health> healthCache = new ConcurrentHashMap<>();
    private final PerformanceService performance;
    private ca.twintigers.research.ports.SymbolSearch search;
    private ca.twintigers.research.domain.portfolio.XRay.Limits xrayLimits = ca.twintigers.research.domain.portfolio.XRay.Limits.fromMap(Map.of());
    private final Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> guessed = new ConcurrentHashMap<>();

    /** Optional parts set by the composition root: symbol search (sector fallback) and the X-ray thresholds. */
    public CockpitService configure(ca.twintigers.research.ports.SymbolSearch search, ca.twintigers.research.domain.portfolio.XRay.Limits limits) {
        this.search = search;
        if (limits != null) {
            this.xrayLimits = limits;
        }
        return this;
    }

    public CockpitService(PortfolioSource portfolio, WatchlistStore watchlist, AlertStore alerts, MarketData market,
                          PriceSignalService prices, FinancialHealthService health, Map<String, String> secTickers, Clock clock) {
        this(portfolio, watchlist, alerts, market, prices, health, secTickers, clock, null);
    }

    /** @param performance returns (the source's own, or computed from its transactions); null = only a source's own */
    public CockpitService(PortfolioSource portfolio, WatchlistStore watchlist, AlertStore alerts, MarketData market,
                          PriceSignalService prices, FinancialHealthService health, Map<String, String> secTickers, Clock clock,
                          PerformanceService performance) {
        this.performance = performance;
        this.portfolio = portfolio;
        this.watchlist = watchlist;
        this.alerts = alerts;
        this.market = market;
        this.prices = prices;
        this.health = health;
        this.secTickers = Map.copyOf(secTickers);
        this.clock = clock;
    }

    /** Daily bars with today's bar dropped until the session is final (16:15 Eastern), as the price rules do. */
    private List<Bar> finalBars(String symbol, int days) throws DataSourceException {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(clock.withZone(AlertEngine.EASTERN));
        List<Bar> bars = market.dailyBars(symbol, now.toLocalDate().minusDays(days), now.toLocalDate());
        if (!bars.isEmpty() && bars.getLast().date().equals(now.toLocalDate())
                && now.toLocalTime().isBefore(ca.twintigers.research.domain.market.MarketCalendar.FINAL_AFTER)) {
            bars = bars.subList(0, bars.size() - 1);
        }
        return bars;
    }

    public String sourceName() {
        return portfolio.name();
    }

    /** Fundamentals per issuer, cached for the life of the process (SEC data changes with filings, not by the minute). */
    public Health health(String symbol) {
        String issuer = Portfolio.issuerOf(symbol);
        return healthCache.computeIfAbsent(issuer, i -> {
            String sec = secTickers.getOrDefault(i, i);
            if (sec.isBlank()) {
                return new Health(null, List.of(), null, "No SEC filings (this company files on SEDAR+), so no health audit.");
            }
            try {
                HealthTable t = health.health(sec, 3).table();
                return new Health(sec, t.flags(), t, null);
            } catch (DataSourceException | RuntimeException e) {
                return new Health(sec, List.of(), null, e.getMessage());
            }
        });
    }

    public Signals signals(String symbol, int days) {
        return signals(symbol, days, Severity.WATCH);
    }

    /** Signals at or above {@code min} severity (the stock page loads all, and filters on the chart). */
    public Signals signals(String symbol, int days, Severity min) {
        try {
            var r = prices.signals(symbol, days, min);
            return new Signals(symbol, r.asOf(), r.signals(), null);
        } catch (DataSourceException | RuntimeException e) {
            return new Signals(symbol, null, List.of(), e.getMessage());
        }
    }

    public PortfolioView portfolioView(double limitPct, int signalDays) {
        try {
            Portfolio p = portfolio.portfolio();
            List<Account> accounts = portfolio.capabilities().contains(PortfolioSource.Capability.ACCOUNTS) ? portfolio.accounts() : List.of();
            Optional<Performance> ytd = Optional.empty();
            try {
                if (performance != null) {
                    ytd = performance.performance("ytd");
                } else if (portfolio.capabilities().contains(PortfolioSource.Capability.PERFORMANCE)) {
                    ytd = portfolio.performance("ytd");
                }
            } catch (DataSourceException | RuntimeException e) {
                ytd = Optional.empty();
            }
            Map<String, Health> h = new LinkedHashMap<>();
            Map<String, Signals> s = new LinkedHashMap<>();
            for (Portfolio.Position pos : p.positions()) {
                h.putIfAbsent(Portfolio.issuerOf(pos.symbol()), health(pos.symbol()));
                s.put(pos.symbol(), signals(pos.symbol(), signalDays));
            }
            return new PortfolioView(portfolio.name(), p, p.issuerWeights(limitPct), accounts, ytd, h, s, limitPct, null);
        } catch (DataSourceException | RuntimeException e) {
            return new PortfolioView(portfolio.name(), null, List.of(), List.of(), Optional.empty(), Map.of(), Map.of(), limitPct,
                    e.getMessage());
        }
    }

    /** Symbols held now (empty when the holdings source cannot answer). */
    public List<String> heldSymbols() {
        try {
            return portfolio.portfolio().positions().stream().map(Portfolio.Position::symbol).toList();
        } catch (DataSourceException | RuntimeException e) {
            return List.of();
        }
    }

    public List<WatchRow> watchlistView(int signalDays) {
        List<String> held = heldSymbols();
        List<Alert> active = alerts.alerts(false);
        List<WatchRow> rows = new ArrayList<>();
        for (WatchItem w : watchlist.watchlist()) {
            try {
                List<Bar> bars = finalBars(w.symbol(), 372);
                if (bars.isEmpty()) {
                    throw new DataSourceException("No daily bars for " + w.symbol() + ".");
                }
                Bar last = bars.getLast();
                Double change = bars.size() > 1 ? (last.close() / bars.get(bars.size() - 2).close() - 1) * 100 : null;
                double hi = bars.stream().mapToDouble(Bar::high).max().orElse(Double.NaN);
                double lo = bars.stream().mapToDouble(Bar::low).min().orElse(Double.NaN);
                Alert nearest = null;
                Double dist = null;
                for (Alert a : active) {
                    if (a.symbol().equalsIgnoreCase(w.symbol()) && a.level() != null
                            && (a.kind() == Alert.Kind.ABOVE || a.kind() == Alert.Kind.BELOW)) {
                        double d = (a.level() / last.close() - 1) * 100;
                        if (dist == null || Math.abs(d) < Math.abs(dist)) {
                            dist = d;
                            nearest = a;
                        }
                    }
                }
                rows.add(new WatchRow(w, held.contains(w.symbol()), last, change, hi, lo, nearest, dist, signals(w.symbol(), signalDays),
                        health(w.symbol()), null));
            } catch (DataSourceException | RuntimeException e) {
                rows.add(new WatchRow(w, held.contains(w.symbol()), null, null, null, null, null, null, null, null, e.getMessage()));
            }
        }
        return rows;
    }

    /** Daily bars for the chart, today's (still forming) bar included, as a quote page shows it. */
    public List<Bar> chartBars(String symbol, LocalDate from) throws DataSourceException {
        return market.dailyBars(symbol.strip().toUpperCase(java.util.Locale.ROOT), from, LocalDate.now(clock));
    }

    /** All signals (info and up) of the last year, for the chart's markers. */
    public Signals chartSignals(String symbol) {
        return signals(symbol.strip().toUpperCase(java.util.Locale.ROOT), 365, Severity.INFO);
    }

    public List<Alert> alertsFor(String symbol) {
        return alerts.alerts(false).stream().filter(a -> a.symbol().equalsIgnoreCase(symbol.strip())).toList();
    }

    public String marketSource() {
        return market.source();
    }

    public StockView stockView(String symbol, int days) {
        String s = symbol.strip().toUpperCase(java.util.Locale.ROOT);
        boolean watched = watchlist.watchlist().stream().anyMatch(w -> w.symbol().equals(s));
        List<Alert> mine = alerts.alerts(false).stream().filter(a -> a.symbol().equalsIgnoreCase(s)).toList();
        try {
            List<Bar> bars = finalBars(s, days);
            if (bars.isEmpty()) {
                throw new DataSourceException("No daily bars for " + s + ".");
            }
            double[] close = bars.stream().mapToDouble(Bar::close).toArray();
            return new StockView(s, heldSymbols().contains(s), watched, bars, Indicators.sma(close, 20), Indicators.atr(bars, 14),
                    signals(s, days, Severity.INFO), mine, health(s), market.source(), null);
        } catch (DataSourceException | RuntimeException e) {
            return new StockView(s, false, watched, List.of(), new double[0], new double[0], null, mine, health(s), market.source(),
                    e.getMessage());
        }
    }

    /** All recorded transactions, newest first (empty when the source has no activity history). */
    static final Map<String, String> RANGE_LABELS = Map.of("1d", "Latest trading day", "wtd", "Week to date", "mtd", "Month to date",
            "ytd", "Year to date", "1y", "Last 12 months", "5y", "Last 5 years", "max", "Since the first transaction");

    /** Returns for every range and past year, dividends and allocation (the Performance page). */
    public PerformanceView performanceView() {
        String base = "CAD";
        try {
            Portfolio p = portfolio.portfolio();
            base = p.baseCurrency() == null ? base : p.baseCurrency();
            List<Account> accts = portfolio.capabilities().contains(PortfolioSource.Capability.ACCOUNTS) ? portfolio.accounts() : List.of();
            List<ReturnRow> rows = new ArrayList<>();
            List<ca.twintigers.research.domain.portfolio.Dividends.Month> divs = List.of();
            List<ca.twintigers.research.domain.portfolio.Dividends.Missing> missing = List.of();
            if (performance != null) {
                List<String> ranges = new ArrayList<>(List.of("1d", "wtd", "mtd", "ytd", "1y", "max"));
                int thisYear = LocalDate.now(clock).getYear();
                performance.years().stream().filter(y -> Integer.parseInt(y) < thisYear).forEach(ranges::add);
                var computed = performance.reports(ranges);
                boolean own = portfolio.capabilities().contains(PortfolioSource.Capability.PERFORMANCE);
                for (String r : ranges) {
                    Optional<Performance> o = Optional.empty();
                    if (own) {
                        try {
                            o = portfolio.performance(r);
                        } catch (DataSourceException e) {
                            o = Optional.empty();
                        }
                    }
                    rows.add(new ReturnRow(r, RANGE_LABELS.getOrDefault(r, "Year " + r), o, computed.get(r)));
                }
                divs = performance.dividendIncome();
                try {
                    missing = performance.missingDividends();
                } catch (DataSourceException e) {
                    missing = List.of();
                }
            }
            return new PerformanceView(portfolio.name(), base, rows, divs, missing,
                    ca.twintigers.research.domain.portfolio.Allocation.byHolding(p),
                    ca.twintigers.research.domain.portfolio.Allocation.byCurrency(p, accts),
                    ca.twintigers.research.domain.portfolio.Allocation.byAccount(p, accts), null);
        } catch (DataSourceException | RuntimeException e) {
            return new PerformanceView(portfolio.name(), base, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Daily value and return over a range, for the Performance chart. */
    public ca.twintigers.research.domain.portfolio.PerformanceCalculator.Report performanceSeries(String range) throws DataSourceException {
        if (performance == null) {
            throw new DataSourceException("Returns are not available.");
        }
        return performance.report(range);
    }

    // --- per-holding P/L, analysis, profiles, X-ray, FIRE (the Ghostfolio screens) ---------------------------

    public List<ca.twintigers.research.domain.portfolio.HoldingReturns.Holding> holdingReturns() {
        try {
            return performance == null ? List.of() : performance.holdings();
        } catch (DataSourceException | RuntimeException e) {
            return List.of();
        }
    }

    public Optional<ca.twintigers.research.domain.portfolio.HoldingReturns.Holding> holdingReturn(String symbol) {
        return holdingReturns().stream().filter(h -> h.symbol().equalsIgnoreCase(symbol)).findFirst();
    }

    public List<ca.twintigers.research.domain.portfolio.Activity> transactionsFor(String symbol) {
        try {
            return portfolio.activities().stream().filter(a -> symbol.equalsIgnoreCase(a.symbol())).toList();
        } catch (DataSourceException | RuntimeException e) {
            return List.of();
        }
    }

    public List<ca.twintigers.research.domain.portfolio.Contributions.Month> contributions() {
        try {
            return performance == null ? List.of() : performance.contributions();
        } catch (DataSourceException | RuntimeException e) {
            return List.of();
        }
    }

    public List<ca.twintigers.research.domain.portfolio.PerformanceCalculator.Point> benchmark(String symbol, LocalDate from, LocalDate to) {
        try {
            return performance == null ? List.of() : performance.benchmark(symbol, from, to);
        } catch (DataSourceException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Sector, country and asset class per held symbol: the holdings source's profiles; for symbols it does not know,
     * an estimate from Yahoo's search (sector) and the listing (country: .TO/.V/.NE = Canada, else United States).
     * Estimated symbols are listed in {@link #estimatedProfiles()}.
     */
    public Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> profiles() {
        Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> out = new LinkedHashMap<>();
        try {
            out.putAll(portfolio.profiles());
        } catch (DataSourceException | RuntimeException e) {
            // fall back to estimates below
        }
        for (String s : heldSymbols()) {
            if (out.containsKey(s)) {
                continue;
            }
            out.put(s, guessed.computeIfAbsent(s, this::guess));
        }
        return out;
    }

    public java.util.Set<String> estimatedProfiles() {
        return java.util.Set.copyOf(guessed.keySet());
    }

    private ca.twintigers.research.domain.portfolio.InstrumentProfile guess(String symbol) {
        String sector = null;
        String sub = "STOCK";
        if (search != null) {
            try {
                for (var m : search.search(symbol, 5)) {
                    if (m.symbol().equalsIgnoreCase(symbol)) {
                        sector = m.sector();
                        sub = "ETF".equals(m.type()) ? "ETF" : "STOCK";
                        break;
                    }
                }
            } catch (DataSourceException | RuntimeException e) {
                // no estimate
            }
        }
        String country = symbol.endsWith(".TO") || symbol.endsWith(".V") || symbol.endsWith(".NE") ? "CA" : "US";
        return new ca.twintigers.research.domain.portfolio.InstrumentProfile(symbol, "EQUITY", sub,
                sector == null ? Map.of() : Map.of(sector, 1.0), "ETF".equals(sub) ? Map.of() : Map.of(country, 1.0));
    }

    public record XRayView(List<ca.twintigers.research.domain.portfolio.XRay.Check> checks, java.util.Set<String> estimated, String error) {
    }

    public XRayView xray() {
        try {
            Portfolio p = portfolio.portfolio();
            List<Account> accts = portfolio.capabilities().contains(PortfolioSource.Capability.ACCOUNTS) ? portfolio.accounts() : List.of();
            var holdings = holdingReturns();
            java.math.BigDecimal fees = holdings.stream().map(h -> h.fees()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal bought = java.math.BigDecimal.ZERO;
            for (var a : portfolio.activities()) {
                if ("BUY".equalsIgnoreCase(a.type())) {
                    bought = bought.add(a.quantity().multiply(a.unitPrice()));
                }
            }
            return new XRayView(ca.twintigers.research.domain.portfolio.XRay.run(p, accts, profiles(), fees, bought, xrayLimits),
                    estimatedProfiles(), null);
        } catch (DataSourceException | RuntimeException e) {
            return new XRayView(List.of(), java.util.Set.of(), e.getMessage());
        }
    }

    /** Units of {@code to} per unit of {@code from} at the latest daily close (Yahoo FROMTO=X); null when unknown. */
    public Double fxRate(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) {
            return 1.0;
        }
        try {
            LocalDate today = LocalDate.now(clock);
            List<Bar> bars = market.dailyBars(from.toUpperCase(java.util.Locale.ROOT) + to.toUpperCase(java.util.Locale.ROOT) + "=X",
                    today.minusDays(10), today);
            return bars.isEmpty() ? null : bars.getLast().close();
        } catch (DataSourceException | RuntimeException e) {
            return null;
        }
    }

    public java.math.BigDecimal netWorth() throws DataSourceException {
        return portfolio.portfolio().netWorthInBase();
    }

    public List<Account> accounts() throws DataSourceException {
        return portfolio.accounts();
    }

    public List<ca.twintigers.research.domain.portfolio.Activity> transactions() throws DataSourceException {
        return portfolio.activities();
    }

    public List<String> watchlistSymbols() {
        return watchlist.watchlist().stream().map(WatchItem::symbol).toList();
    }

    public WatchItem watch(String symbol, String note) {
        return watchlist.watch(symbol, note);
    }

    public boolean unwatch(String symbol) {
        return watchlist.unwatch(symbol);
    }
}
