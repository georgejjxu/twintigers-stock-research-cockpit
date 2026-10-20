package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.config.Holidays;
import ca.twintigers.research.adapters.config.Settings;
import ca.twintigers.research.adapters.ghostfolio.GhostfolioClient;
import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.adapters.market.AlphaVantageClient;
import ca.twintigers.research.adapters.market.CachedMarketData;
import ca.twintigers.research.adapters.market.FallbackMarketData;
import ca.twintigers.research.adapters.market.YahooChartClient;
import ca.twintigers.research.adapters.notify.NtfyNotifier;
import ca.twintigers.research.adapters.notify.WindowsToastNotifier;
import ca.twintigers.research.adapters.sec.SecEdgarClient;
import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.adapters.store.SqliteAlertDb;
import ca.twintigers.research.adapters.store.SqliteBarStore;
import ca.twintigers.research.adapters.store.SqliteWatchlist;
import ca.twintigers.research.adapters.local.LocalPortfolioSource;
import ca.twintigers.research.application.AlertEngine;
import ca.twintigers.research.application.AlertService;
import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.application.FinancialHealthService;
import ca.twintigers.research.application.LedgerSync;
import ca.twintigers.research.application.NotificationDispatcher;
import ca.twintigers.research.application.PerformanceService;
import ca.twintigers.research.application.PortfolioReviewService;
import ca.twintigers.research.application.PriceSignalService;
import ca.twintigers.research.application.SourceParity;
import ca.twintigers.research.domain.fundamentals.Thresholds;
import ca.twintigers.research.domain.market.MarketCalendar;
import ca.twintigers.research.domain.market.PriceRuleConfig;
import ca.twintigers.research.ports.LedgerBook;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.Notifier;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.WatchlistStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Manual dependency injection: builds the services from {@link Settings}. No framework. */
public final class Wiring {

    public final Settings settings;
    public final Clock clock;
    public final Thresholds thresholds;
    public final PriceRuleConfig priceConfig;
    public final SecEdgarClient sec;
    public final MarketData marketData;
    public final YahooChartClient yahoo;
    /** Delayed intraday bars, stored by symbol and interval (V7). */
    public final ca.twintigers.research.ports.IntradayData intraday;
    /** The portfolio source chosen by PORTFOLIO_SOURCE (ghostfolio | local). Apps use only this port. */
    public final PortfolioSource portfolio;
    /** Ghostfolio, whichever source is chosen (reference for the sync and the parity check). */
    public final PortfolioSource ghostfolio;
    /** The local ledger source, whichever source is chosen, and its mirror interface. */
    public final PortfolioSource local;
    public final LedgerBook ledger;
    /** Returns of the chosen source (its own figure when it has one), and computed from the local ledger. */
    public final PerformanceService performance;
    public final PerformanceService ledgerPerformance;
    public final LedgerSync ledgerSync = new LedgerSync();
    public final SourceParity parity;
    public final String baseCurrency;
    /** Records transactions in the same source (Ghostfolio only when GHOSTFOLIO_WRITES=enabled). */
    public final ca.twintigers.research.ports.PortfolioWriter writer;
    public final WatchlistStore watchlist;
    public final FinancialHealthService health;
    public final PriceSignalService prices;
    public final PortfolioReviewService review;
    public final Database database;
    public final SqliteAlertDb alertDb;
    public final List<Notifier> notifiers;
    public final NotificationDispatcher dispatcher;
    public final MarketCalendar calendar;
    public final AlertService alerts;
    public final AlertEngine engine;
    public final CockpitService cockpit;
    /** Markets page: Fear and Greed and benchmark indices. */
    public final ca.twintigers.research.application.MarketService markets;

    public Wiring(Settings settings) {
        this(settings, Clock.system(ZoneId.of("America/Toronto")));
    }

    public Wiring(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.thresholds = Thresholds.fromMap(settings.rules());
        this.priceConfig = PriceRuleConfig.fromMap(settings.rules());
        Http http = new Http();
        this.sec = new SecEdgarClient(http, settings.get("SEC_USER_AGENT"), settings.cacheDir(),
                "1".equals(settings.get("RESEARCH_OFFLINE")), clock);
        this.database = new Database(settings.cacheDir().resolve("research.db"));
        this.yahoo = new YahooChartClient(http);
        this.intraday = new ca.twintigers.research.adapters.market.CachedIntradayData(yahoo, database, clock);
        List<MarketData> providers = new ArrayList<>(List.of(yahoo));
        String avKey = settings.get("ALPHAVANTAGE_API_KEY");
        if (avKey != null) {
            providers.add(new AlphaVantageClient(http, avKey));
        }
        this.marketData = new CachedMarketData(new FallbackMarketData(providers), new SqliteBarStore(database), clock);
        this.baseCurrency = settings.get("PORTFOLIO_BASE_CURRENCY", "CAD");
        this.ghostfolio = ghostfolioSource(settings, clock);
        LocalPortfolioSource localSource = localSource(settings, marketData, yahoo, database, clock);
        this.local = localSource;
        this.ledger = localSource;
        this.portfolio = portfolioSource(settings, ghostfolio, local);
        this.writer = (ca.twintigers.research.ports.PortfolioWriter) portfolio;
        this.health = new FinancialHealthService(sec, sec, thresholds);
        this.prices = new PriceSignalService(marketData, priceConfig,
                ca.twintigers.research.domain.market.Refinement.fromMap(settings.rules(),
                        ca.twintigers.research.domain.market.PriceRules.all(priceConfig)), clock);
        this.review = new PortfolioReviewService(portfolio, health, prices, PortfolioReviewService.defaultSecTickers());

        this.alertDb = new SqliteAlertDb(database);
        this.watchlist = new SqliteWatchlist(database, clock);
        List<Notifier> ns = new ArrayList<>();
        if (!"0".equals(settings.get("NOTIFY_TOAST", "1")) && System.getProperty("os.name", "").startsWith("Windows")) {
            ns.add(new WindowsToastNotifier());
        }
        String topic = settings.get("NTFY_TOPIC");
        if (topic != null) {
            ns.add(new NtfyNotifier(http, settings.get("NTFY_URL", "https://ntfy.sh"), topic, settings.get("NTFY_TOKEN")));
        }
        this.notifiers = List.copyOf(ns);
        this.dispatcher = new NotificationDispatcher(alertDb, notifiers, clock);
        this.calendar = Holidays.load(holidaysDir(settings));
        this.alerts = new AlertService(alertDb, prices.engine().rules(), clock);
        Duration cooldown = Duration.ofHours(Long.parseLong(settings.rules().getOrDefault("alert.cooldownHours", "24").strip()));
        this.engine = new AlertEngine(portfolio, alertDb, marketData, yahoo, prices, health,
                PortfolioReviewService.defaultSecTickers(), alertDb, alertDb, dispatcher, calendar, cooldown, clock);
        this.performance = new PerformanceService(portfolio, marketData, yahoo, baseCurrency, clock);
        this.ledgerPerformance = new PerformanceService(local, marketData, yahoo, baseCurrency, clock);
        this.parity = new SourceParity(clock);
        this.cockpit = new CockpitService(portfolio, watchlist, alertDb, marketData, prices, health,
                PortfolioReviewService.defaultSecTickers(), clock, performance)
                .configure(yahoo, ca.twintigers.research.domain.portfolio.XRay.Limits.fromMap(settings.rules()));
        this.markets = new ca.twintigers.research.application.MarketService(marketData,
                new ca.twintigers.research.adapters.market.FearGreedClient(http, clock),
                ca.twintigers.research.application.MarketService.parse(settings.rules().get("market.benchmarks")), clock);
    }

    /**
     * The composition root is the only place outside the source adapters that names them. PORTFOLIO_SOURCE:
     * "ghostfolio" (default) or "local" (the ledger: its accounts, or RESEARCH_PORTFOLIO toml until it has some).
     */
    static PortfolioSource portfolioSource(Settings s, PortfolioSource ghostfolio, PortfolioSource local) {
        String source = s.get("PORTFOLIO_SOURCE", "local").strip().toLowerCase(java.util.Locale.ROOT);
        return switch (source) {
            case "ghostfolio" -> ghostfolio;
            case "local" -> local;
            default -> throw new IllegalArgumentException("PORTFOLIO_SOURCE must be ghostfolio or local, not " + source + ".");
        };
    }

    static GhostfolioClient ghostfolioSource(Settings s, Clock clock) {
        return new GhostfolioClient(new Http(Duration.ofSeconds(60), 2), s.get("GHOSTFOLIO_URL", "http://localhost:3333"),
                s.get("GHOSTFOLIO_TOKEN"), "enabled".equalsIgnoreCase(s.get("GHOSTFOLIO_WRITES", "")), clock);
    }

    static LocalPortfolioSource localSource(Settings s, MarketData marketData, YahooChartClient yahoo, Database db, Clock clock) {
        return new LocalPortfolioSource(Path.of(s.get("RESEARCH_PORTFOLIO", "portfolio.toml")), db, marketData, yahoo,
                s.get("PORTFOLIO_BASE_CURRENCY", "CAD"), clock).withNameLookup(yahoo);
    }

    static Path holidaysDir(Settings s) {
        String explicit = s.get("RESEARCH_HOLIDAYS_DIR");
        if (explicit != null) {
            return Path.of(explicit);
        }
        String rules = s.get("RESEARCH_RULES_FILE");
        if (rules != null) {
            return Path.of(rules).toAbsolutePath().getParent().resolve("holidays");
        }
        return Path.of("java", "config", "holidays");
    }
}
