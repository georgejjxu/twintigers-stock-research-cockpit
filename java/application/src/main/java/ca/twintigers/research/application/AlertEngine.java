package ca.twintigers.research.application;

import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.alerts.AlertState;
import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.domain.alerts.Subscription;
import ca.twintigers.research.domain.alerts.ThresholdCheck;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.MarketCalendar;
import ca.twintigers.research.domain.market.MarketCalendar.Exchange;
import ca.twintigers.research.domain.market.MarketContext;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.domain.market.PriceRuleConfig;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.domain.rules.Flag;
import ca.twintigers.research.domain.rules.Severity;
import ca.twintigers.research.ports.AlertStore;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.HoldingsCache;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.QuoteSource;
import ca.twintigers.research.ports.RunLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One monitoring run.
 * <ul>
 *   <li><b>close</b> (after 16:15 Eastern on a trading day): price rules on the day's final bar, threshold alerts on
 *       the close, fundamental flags (they change only when a filing arrives; deduplicated by period).</li>
 *   <li><b>intraday</b> (market hours): threshold alerts on the latest delayed quote only.</li>
 *   <li><b>auto</b>: picks intraday, close (once per day) or nothing from the clock and the exchange calendars.</li>
 * </ul>
 * Every run records a heartbeat, warns once if the previous successful run is more than a trading day old,
 * falls back to the last known holdings when Ghostfolio is down, and finally dispatches the outbox.
 */
public final class AlertEngine {

    public static final ZoneId EASTERN = ZoneId.of("America/Toronto");

    public enum Mode { AUTO, CLOSE, INTRADAY }

    public record RunResult(String mode, boolean ran, int symbols, int queued, NotificationDispatcher.Result dispatch,
                            List<String> notes) {
    }

    private final PortfolioSource portfolio;
    private final HoldingsCache holdingsCache;
    private final MarketData market;
    private final QuoteSource quotes;
    private final PriceSignalService prices;
    private final FinancialHealthService health;
    private final Map<String, String> secTickers;
    private final AlertStore store;
    private final RunLog runs;
    private final NotificationDispatcher dispatcher;
    private final MarketCalendar calendar;
    private final Duration cooldown;
    private final Clock clock;

    public AlertEngine(PortfolioSource portfolio, HoldingsCache holdingsCache, MarketData market, QuoteSource quotes,
                       PriceSignalService prices, FinancialHealthService health, Map<String, String> secTickers,
                       AlertStore store, RunLog runs, NotificationDispatcher dispatcher, MarketCalendar calendar,
                       Duration cooldown, Clock clock) {
        this.portfolio = portfolio;
        this.holdingsCache = holdingsCache;
        this.market = market;
        this.quotes = quotes;
        this.prices = prices;
        this.health = health;
        this.secTickers = Map.copyOf(secTickers);
        this.store = store;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.calendar = calendar;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Which mode AUTO resolves to now; empty when there is nothing to do (weekend, night, close run already done). */
    public Optional<Mode> resolve(Mode requested) {
        if (requested != Mode.AUTO) {
            return Optional.of(requested);
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(EASTERN));
        LocalDate d = now.toLocalDate();
        boolean anyTrading = calendar.isTradingDay(Exchange.TSX, d) || calendar.isTradingDay(Exchange.NYSE, d);
        if (!anyTrading) {
            return Optional.empty();
        }
        if (calendar.isOpen(Exchange.TSX, d, now.toLocalTime()) || calendar.isOpen(Exchange.NYSE, d, now.toLocalTime())) {
            return Optional.of(Mode.INTRADAY);
        }
        if (!now.toLocalTime().isBefore(MarketCalendar.FINAL_AFTER)) {
            // only a close run started after today's bars were final counts (a manual daytime run does not)
            boolean closeDoneToday = runs.recentRuns(50).stream().anyMatch(r -> r.ok() && r.mode().equals("close")
                    && r.startedAt().atZone(EASTERN).toLocalDate().equals(d)
                    && !r.startedAt().atZone(EASTERN).toLocalTime().isBefore(MarketCalendar.FINAL_AFTER));
            return closeDoneToday ? Optional.empty() : Optional.of(Mode.CLOSE);
        }
        return Optional.empty();
    }

    public RunResult run(Mode requested) {
        Optional<Mode> mode = resolve(requested);
        if (mode.isEmpty()) {
            NotificationDispatcher.Result d = dispatcher.dispatch(); // still retry anything queued
            return new RunResult("auto: nothing to do now", false, 0, 0, d, List.of());
        }
        String m = mode.get().name().toLowerCase(java.util.Locale.ROOT);
        Instant start = clock.instant();
        List<String> notes = new ArrayList<>();
        int queued = staleCheck(notes);
        long runId = runs.start(m, start);
        int symbolsEvaluated = 0;
        try {
            List<String> symbols = symbols(notes);
            symbolsEvaluated = symbols.size();
            queued += thresholds(mode.get(), notes);
            if (mode.get() == Mode.CLOSE) {
                queued += priceRules(symbols, notes);
                queued += fundamentals(symbols, notes);
            }
            NotificationDispatcher.Result d = dispatcher.dispatch();
            runs.finish(runId, clock.instant(), true, symbolsEvaluated, queued, String.join(" | ", notes));
            return new RunResult(m, true, symbolsEvaluated, queued, d, notes);
        } catch (RuntimeException e) {
            runs.finish(runId, clock.instant(), false, symbolsEvaluated, queued, e.toString());
            throw e;
        }
    }

    /** Held symbols (Ghostfolio, else the cached list) plus the symbols of active threshold alerts. */
    List<String> symbols(List<String> notes) {
        Set<String> out = new LinkedHashSet<>();
        try {
            List<String> held = portfolio.portfolio().positions().stream().map(Portfolio.Position::symbol).toList();
            holdingsCache.save(held, clock.instant());
            out.addAll(held);
        } catch (DataSourceException | RuntimeException e) {
            Optional<HoldingsCache.Saved> cached = holdingsCache.load();
            cached.ifPresent(c -> out.addAll(c.symbols()));
            notes.add("Ghostfolio unavailable (" + e.getMessage() + "); used holdings cached at "
                    + cached.map(c -> c.at().toString()).orElse("never"));
        }
        store.alerts(false).forEach(a -> out.add(a.symbol()));
        return List.copyOf(out);
    }

    private int staleCheck(List<String> notes) {
        Optional<RunLog.Run> last = runs.lastSuccessfulEvaluation();
        if (last.isEmpty()) {
            return 0;
        }
        LocalDate lastDay = last.get().finishedAt().atZone(EASTERN).toLocalDate();
        LocalDate today = LocalDate.now(clock.withZone(EASTERN));
        LocalDate prevTrading = calendar.previousTradingDay(Exchange.NYSE, today);
        if (lastDay.isBefore(prevTrading)) {
            notes.add("monitor was stale since " + lastDay);
            return dispatcher.enqueue(new Notification("stale|" + lastDay, "Research monitor was not running",
                    "No successful monitoring run between " + lastDay + " and today (" + today + "). Alerts for the days "
                            + "in between were not checked. Is the PC on and the scheduled task enabled?",
                    Notification.Priority.HIGH));
        }
        return 0;
    }

    private int thresholds(Mode mode, List<String> notes) {
        int queued = 0;
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(EASTERN));
        for (Alert a : store.alerts(false)) {
            Exchange x = MarketCalendar.exchangeOf(a.symbol());
            if (mode == Mode.INTRADAY && !calendar.isOpen(x, today, ZonedDateTime.now(clock.withZone(EASTERN)).toLocalTime())) {
                continue;
            }
            try {
                List<Bar> bars = finalBars(a.symbol(), 400);
                double price;
                String when;
                if (mode == Mode.INTRADAY) {
                    QuoteSource.Quote q = quotes.latest(a.symbol());
                    price = q.price();
                    when = q.time().atZone(EASTERN).toLocalDateTime().withNano(0) + " ET (" + q.source() + ", delayed)";
                } else {
                    Bar last = bars.getLast();
                    price = last.close();
                    when = "close of " + last.date();
                }
                ThresholdCheck.Result r = ThresholdCheck.evaluate(a, price, when, bars);
                AlertState.Transition t = a.state().evaluate(r.condition(), now, cooldown);
                store.updateState(a.id(), t.next());
                if (t.shouldNotify() && !a.muted(now)) {
                    queued += dispatcher.enqueue(new Notification("alert|" + a.id() + "|" + t.next().since(),
                            a.symbol() + " · your alert #" + a.id(), r.text() + " Price: " + when + "."
                            + (a.note() == null || a.note().isBlank() ? "" : " Note: " + a.note()), Notification.Priority.HIGH));
                }
            } catch (DataSourceException | RuntimeException e) {
                notes.add("alert #" + a.id() + " " + a.symbol() + ": " + e.getMessage());
            }
        }
        return queued;
    }

    /** Daily bars with today's bar removed if the session is not final yet. */
    private List<Bar> finalBars(String symbol, int days) throws DataSourceException {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(EASTERN));
        List<Bar> bars = market.dailyBars(symbol, now.toLocalDate().minusDays(days), now.toLocalDate());
        if (!bars.isEmpty() && bars.getLast().date().equals(now.toLocalDate()) && now.toLocalTime().isBefore(MarketCalendar.FINAL_AFTER)) {
            bars = bars.subList(0, bars.size() - 1);
        }
        if (bars.isEmpty()) {
            throw new DataSourceException("no daily bars for " + symbol);
        }
        return bars;
    }

    private int priceRules(List<String> symbols, List<String> notes) {
        int queued = 0;
        Instant now = clock.instant();
        List<Subscription> subs = store.subscriptions(false);
        PriceRuleConfig cfg = prices.config();
        for (String s : symbols) {
            try {
                List<Bar> bars = finalBars(s, 400);
                MarketContext ctx = new MarketContext(s, bars, cfg);
                int last = ctx.size() - 1;
                for (PriceRule rule : prices.engine().rules()) {
                    Optional<Signal> sig = rule.evaluate(ctx, last);
                    if (sig.isEmpty()) {
                        continue;
                    }
                    Signal g = sig.get();
                    if (subs.stream().anyMatch(x -> x.matches(s, Subscription.Source.PRICE, g.ruleId(), g.severity(), now))) {
                        queued += dispatcher.enqueue(new Notification("signal|" + s + "|" + g.ruleId() + "|" + g.date(),
                                s + " · " + g.ruleName() + " (" + g.severity().label() + ")",
                                g.text() + " Bar of " + g.date() + ", close " + ctx.bar(last).close() + ".",
                                g.severity() == Severity.WARNING ? Notification.Priority.HIGH : Notification.Priority.DEFAULT));
                    }
                }
            } catch (DataSourceException | RuntimeException e) {
                notes.add(s + ": " + e.getMessage());
            }
        }
        return queued;
    }

    private int fundamentals(List<String> symbols, List<String> notes) {
        int queued = 0;
        Instant now = clock.instant();
        List<Subscription> subs = store.subscriptions(false);
        Set<String> issuers = new LinkedHashSet<>();
        symbols.forEach(s -> issuers.add(Portfolio.issuerOf(s)));
        for (String issuer : issuers) {
            String sec = secTickers.getOrDefault(issuer, issuer);
            if (sec.isBlank()) {
                continue;
            }
            try {
                var table = health.health(sec, 3).table();
                String period = table.columns().getLast().period().end().toString();
                for (Flag f : table.flags()) {
                    if (subs.stream().anyMatch(x -> x.matches(issuer, Subscription.Source.FUNDAMENTAL, f.ruleId(), f.severity(), now))) {
                        queued += dispatcher.enqueue(new Notification("fundamental|" + issuer + "|" + f.ruleId() + "|" + period,
                                issuer + " · fundamentals (" + f.severity().label() + ")",
                                f.text() + " (SEC filings, latest period " + period + ".)", Notification.Priority.DEFAULT));
                    }
                }
            } catch (DataSourceException | RuntimeException e) {
                notes.add(issuer + " fundamentals: " + e.getMessage());
            }
        }
        return queued;
    }
}
