package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.MarketContext;
import ca.twintigers.research.domain.market.PriceRuleConfig;
import ca.twintigers.research.domain.market.PriceRules;
import ca.twintigers.research.domain.market.RuleEngine;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Use cases: recent price signals for a symbol, and a replay of every rule over its history. */
public final class PriceSignalService {

    /** Calendar days of extra history loaded before the window, for indicators and support/resistance levels. */
    static final int HISTORY_DAYS = 400;

    /**
     * @param lastBar the newest bar used (its date is the "as of" of the signals)
     */
    public record SignalReport(String symbol, String source, LocalDate from, LocalDate asOf, Bar lastBar,
                               int barsUsed, List<Signal> signals) {
    }

    public record ReplayReport(String symbol, String source, RuleEngine.Replay replay) {
    }

    private final MarketData market;
    private final RuleEngine engine;
    private final PriceRuleConfig config;
    private final Clock clock;

    public PriceSignalService(MarketData market, PriceRuleConfig config, Clock clock) {
        this(market, config, java.util.Map.of(), clock);
    }

    /** @param refinements per-rule refinements and severity overrides from the rules configuration (tuned by replay) */
    public PriceSignalService(MarketData market, PriceRuleConfig config,
                              java.util.Map<String, ca.twintigers.research.domain.market.Refinement> refinements, Clock clock) {
        this.market = market;
        this.config = config;
        this.engine = new RuleEngine(ca.twintigers.research.domain.market.Refinement.applyAll(PriceRules.all(config), refinements));
        this.clock = clock;
    }

    public MarketData market() {
        return market;
    }

    public RuleEngine engine() {
        return engine;
    }

    public PriceRuleConfig config() {
        return config;
    }

    /** Signals on the trading days of the last {@code lookbackDays} calendar days. */
    public SignalReport signals(String symbol, int lookbackDays) throws DataSourceException {
        return signals(symbol, lookbackDays, ca.twintigers.research.domain.rules.Severity.INFO);
    }

    /** As {@link #signals(String, int)}, keeping only signals at or above {@code minSeverity}. */
    public SignalReport signals(String symbol, int lookbackDays, ca.twintigers.research.domain.rules.Severity minSeverity)
            throws DataSourceException {
        LocalDate today = LocalDate.now(clock);
        LocalDate windowStart = today.minusDays(Math.max(1, lookbackDays));
        List<Bar> bars = market.dailyBars(symbol, windowStart.minusDays(HISTORY_DAYS), today);
        java.time.ZonedDateTime nowEt = java.time.ZonedDateTime.now(clock.withZone(java.time.ZoneId.of("America/Toronto")));
        if (!bars.isEmpty() && bars.getLast().date().equals(nowEt.toLocalDate())
                && nowEt.toLocalTime().isBefore(ca.twintigers.research.domain.market.MarketCalendar.FINAL_AFTER)) {
            bars = bars.subList(0, bars.size() - 1); // today's session is not over: its bar is partial
        }
        if (bars.isEmpty()) {
            throw new DataSourceException("No daily bars for " + symbol + " from " + market.source() + ".");
        }
        MarketContext ctx = new MarketContext(symbol, bars, config);
        int from = 0;
        while (from < bars.size() && bars.get(from).date().isBefore(windowStart)) {
            from++;
        }
        List<Signal> signals = engine.scan(ctx, from).stream()
                .filter(s -> s.severity().compareTo(minSeverity) >= 0).toList();
        return new SignalReport(symbol, market.source(), windowStart, bars.getLast().date(), bars.getLast(), bars.size(),
                signals);
    }

    /** Every rule over the last {@code years} years of history, with how often each fired and what followed. */
    public ReplayReport replay(String symbol, int years) throws DataSourceException {
        LocalDate today = LocalDate.now(clock);
        List<Bar> bars = market.dailyBars(symbol, today.minusYears(Math.max(1, years)).minusDays(90), today);
        if (bars.size() <= RuleEngine.WARMUP) {
            throw new DataSourceException("Not enough daily bars for " + symbol + " (" + bars.size() + ").");
        }
        return new ReplayReport(symbol, market.source(), engine.replay(new MarketContext(symbol, bars, config)));
    }
}
