package ca.twintigers.research.web;

import ca.twintigers.research.application.AlertService;
import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.ports.Outbox;
import ca.twintigers.research.ports.RunLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stock Research Cockpit web server (JDK built-in HTTP server, no framework).
 * <ul>
 *   <li>Listens on 127.0.0.1 (in the Docker container on the container's own interfaces, published to the PC's
 *       127.0.0.1 only), and answers only requests addressed to localhost/127.0.0.1 (DNS-rebinding guard).</li>
 *   <li>Every POST needs the per-start random token (sent by htmx in the X-CSRF-Token header) and, when present, a
 *       local Origin.</li>
 *   <li>Content-Security-Policy: scripts only from this server; no framing.</li>
 *   <li>Writes the Cockpit's own data (watch list, alerts, subscriptions) and, from the Transactions page after a
 *       confirmation, transactions through the PortfolioWriter.</li>
 * </ul>
 */
public final class CockpitServer {

    /** What the pages need besides the cockpit service; supplied by the composition root. */
    public record Deps(CockpitService cockpit, AlertService alerts, Supplier<List<String>> channels,
                       Supplier<List<RunLog.Run>> runs, Supplier<List<Outbox.Entry>> notifications, List<PriceRule> rules,
                       ca.twintigers.research.ports.PortfolioWriter writer, ca.twintigers.research.ports.SymbolSearch search,
                       ca.twintigers.research.ports.IntradayData intraday, ca.twintigers.research.application.MarketService markets) {

        public Deps(CockpitService cockpit, AlertService alerts, Supplier<List<String>> channels, Supplier<List<RunLog.Run>> runs,
                    Supplier<List<Outbox.Entry>> notifications, List<PriceRule> rules, ca.twintigers.research.ports.PortfolioWriter writer,
                    ca.twintigers.research.ports.SymbolSearch search, ca.twintigers.research.ports.IntradayData intraday) {
            this(cockpit, alerts, channels, runs, notifications, rules, writer, search, intraday, null);
        }

        public Deps(CockpitService cockpit, AlertService alerts, Supplier<List<String>> channels, Supplier<List<RunLog.Run>> runs,
                    Supplier<List<Outbox.Entry>> notifications, List<PriceRule> rules, ca.twintigers.research.ports.PortfolioWriter writer) {
            this(cockpit, alerts, channels, runs, notifications, rules, writer, (q, max) -> List.of(), (s, i, f) -> {
                throw new ca.twintigers.research.ports.DataSourceException("No intraday data source is configured.");
            }, null);
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange x, Matcher m) throws Exception;
    }

    private record Route(String method, Pattern path, Handler handler) {
    }

    private final Deps d;
    private final HttpServer server;
    private final String csrf;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Route> routes = new java.util.ArrayList<>();
    private final int port;

    public CockpitServer(Deps deps, int port) throws IOException {
        this(deps, InetAddress.getLoopbackAddress(), port);
    }

    /** @param bind address to listen on: loopback, except inside the container (published to the PC's 127.0.0.1 only) */
    public CockpitServer(Deps deps, InetAddress bind, int port) throws IOException {
        this.d = deps;
        byte[] r = new byte[24];
        new SecureRandom().nextBytes(r);
        this.csrf = HexFormat.of().formatHex(r);
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.port = server.getAddress().getPort();
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::dispatch);
        routes();
    }

    public int port() {
        return port;
    }

    public String csrfToken() {
        return csrf;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void get(String path, Handler h) {
        routes.add(new Route("GET", Pattern.compile(path), h));
    }

    private void post(String path, Handler h) {
        routes.add(new Route("POST", Pattern.compile(path), h));
    }

    private String page(String title, String subtitle, Layout.Nav nav, String body) {
        return Layout.page(title, subtitle, nav, d.cockpit().sourceName(), csrf, body);
    }

    private void routes() {
        get("/", (x, m) -> html(x, page("Portfolio", "Holdings, concentration, fundamentals and recent price signals.", Layout.Nav.PORTFOLIO,
                Pages.portfolio(d.cockpit().portfolioView(20, 30), returnsBySymbol()))));
        get("/watchlist", (x, m) -> html(x, page("Watch List", "Stocks you follow, with distance to your alerts and the latest signals.",
                Layout.Nav.WATCHLIST, Pages.watchlist(d.cockpit().watchlistView(30), null))));
        post("/watchlist", (x, m) -> {
            Map<String, String> f = form(x);
            String msg;
            try {
                boolean existed = d.cockpit().watchlistSymbols().contains(f.getOrDefault("symbol", "").strip().toUpperCase(Locale.ROOT));
                String sym = d.cockpit().watch(f.getOrDefault("symbol", ""), f.get("note")).symbol();
                msg = existed ? "Saved the note for " + sym + "." : "Added " + sym + ".";
            } catch (IllegalArgumentException e) {
                msg = e.getMessage();
            }
            html(x, Pages.watchlistTable(d.cockpit().watchlistView(30), msg));
        });
        post("/watchlist/([A-Za-z0-9.=^-]{1,20})/remove", (x, m) -> {
            boolean ok = d.cockpit().unwatch(m.group(1));
            html(x, Pages.watchlistTable(d.cockpit().watchlistView(30), ok ? "Removed " + m.group(1).toUpperCase(Locale.ROOT) + "." : null));
        });
        get("/stock", (x, m) -> {
            Map<String, String> q = query(x);
            String other = q.getOrDefault("other", "").strip();
            String s = other.isEmpty() ? q.getOrDefault("symbol", "ENB.TO").strip() : other;
            redirect(x, "/stock/" + Html.sym(s.isEmpty() ? "ENB.TO" : s.toUpperCase(Locale.ROOT)));
        });
        get("/performance", (x, m) -> html(x, page("Performance", "Returns by period, value over time, allocation and dividends.",
                Layout.Nav.PERFORMANCE, performanceBody())));
        get("/xray", (x, m) -> html(x, page("X-ray", "How your portfolio is made up, checked against limits you set.", Layout.Nav.XRAY,
                PortfolioScreens.xray(d.cockpit().xray()))));
        get("/fire", (x, m) -> {
            Map<String, String> q = query(x);
            var in = new PortfolioScreens.FireInput(dbl(q, "savings", 0), dbl(q, "return", 4), dbl(q, "rate", 4), dbl(q, "spending", 0),
                    (int) Math.max(1, Math.min(60, dbl(q, "years", 30))));
            String body;
            try {
                var r = ca.twintigers.research.domain.portfolio.Fire.compute(d.cockpit().netWorth().doubleValue(), in.savings(), in.returnPct(),
                        in.ratePct(), in.spending(), in.years(), LocalDate.now().getYear());
                body = PortfolioScreens.fire(r, in, "CAD");
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                body = Layout.notice("The holdings source is not available: " + e.getMessage());
            }
            html(x, page("FIRE", "What your net worth could pay for, at a withdrawal rate you choose.", Layout.Nav.FIRE, body));
        });
        get("/api/performance/(1d|wtd|mtd|ytd|1y|5y|max|\\d{4})", (x, m) -> {
            try {
                send(x, 200, "application/json; charset=utf-8",
                        json.writeValueAsBytes(performanceChart(m.group(1))));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                send(x, 503, "application/json; charset=utf-8", json.writeValueAsBytes(Map.of("error", e.getMessage())));
            }
        });
        get("/api/intraday/([A-Za-z0-9.=^-]{1,20})", (x, m) -> {
            try {
                var interval = ca.twintigers.research.domain.market.Interval.parse(query(x).getOrDefault("interval", "5m"));
                String symbol = m.group(1).toUpperCase(Locale.ROOT);
                var series = d.intraday().intraday(symbol, interval, java.time.Instant.now().minus(interval.defaultLookback()));
                var mine = d.alerts().alerts().stream().filter(a -> a.symbol().equalsIgnoreCase(symbol)).toList();
                send(x, 200, "application/json; charset=utf-8", json.writeValueAsBytes(ChartData.intraday(series, mine)));
            } catch (ca.twintigers.research.ports.DataSourceException | IllegalArgumentException e) {
                send(x, 503, "application/json; charset=utf-8", json.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
            }
        });
        get("/markets", (x, m) -> html(x, page("Markets", "Fear & Greed and the main indices.", Layout.Nav.MARKETS,
                d.markets() == null ? Layout.notice("The Markets page is not configured.") : MarketsPage.page(d.markets().view()))));
        get("/symbols/suggest", (x, m) -> {
            String q = query(x).getOrDefault("other", "").strip();
            List<ca.twintigers.research.ports.SymbolSearch.Match> found;
            try {
                found = q.length() < 2 ? List.of() : d.search().search(q, 10);
            } catch (ca.twintigers.research.ports.DataSourceException | RuntimeException e) {
                found = List.of();
            }
            html(x, Pages.symbolSuggestions(found));
        });
        get("/stock/([A-Za-z0-9.=^-]{1,20})", (x, m) -> {
            var v = d.cockpit().stockView(m.group(1), 365);
            html(x, page(v.symbol(), "Daily K-line chart, price signals, your alerts and fundamentals.", Layout.Nav.STOCK,
                    Pages.stock(v, d.cockpit().heldSymbols(), d.cockpit().watchlistSymbols())
                            + PortfolioScreens.position(v.symbol(), d.cockpit().holdingReturn(v.symbol()), d.cockpit().transactionsFor(v.symbol()),
                            "CAD")));
        });
        get("/api/chart/([A-Za-z0-9.=^-]{1,20})", (x, m) -> {
            Map<String, String> q = query(x);
            Map<String, String> defs = new java.util.HashMap<>();
            d.rules().forEach(r -> defs.put(r.id(), r.definition()));
            try {
                java.time.Clock clock = java.time.Clock.system(java.time.ZoneId.of("America/Toronto"));
                var plan = StockChart.plan(q.get("range"), q.get("from"), q.get("to"), q.get("bars"), LocalDate.now(clock));
                send(x, 200, "application/json; charset=utf-8",
                        json.writeValueAsBytes(StockChart.build(m.group(1), plan, d.cockpit(), d.intraday(), defs, clock)));
            } catch (ca.twintigers.research.ports.DataSourceException | IllegalArgumentException | java.time.DateTimeException e) {
                send(x, 503, "application/json; charset=utf-8", json.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
            }
        });
        get("/alerts", (x, m) -> html(x, page("Alerts", "Your price alerts, rule subscriptions, the monitor and its notifications.",
                Layout.Nav.ALERTS, Pages.alerts(d.alerts().alerts(), d.alerts().subscriptions(), d.channels().get(), d.runs().get(),
                        d.notifications().get(), d.rules()))));
        post("/alerts/threshold", (x, m) -> {
            Map<String, String> f = form(x);
            String msg;
            try {
                var a = d.alerts().addThreshold(f.getOrDefault("kind", "above"), f.getOrDefault("symbol", ""), num(f, "level"), num(f, "pct"),
                        num(f, "reference"), num(f, "atr"), f.get("since") == null || f.get("since").isBlank() ? null : LocalDate.parse(f.get("since")),
                        f.get("note"));
                msg = "Added alert #" + a.id() + ": " + a.describe() + ".";
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                msg = e.getMessage();
            }
            if ("alerts-message".equals(x.getRequestHeaders().getFirst("HX-Target"))) {
                html(x, Layout.notice(msg));
            } else {
                html(x, Pages.alertsTable(d.alerts().alerts(), msg));
            }
        });
        post("/alerts/subscription", (x, m) -> {
            Map<String, String> f = form(x);
            try {
                d.alerts().subscribe(f.getOrDefault("scope", "*"), f.getOrDefault("source", "price"), f.getOrDefault("rule", "*"),
                        f.getOrDefault("minSeverity", "warning"), "user");
                html(x, Pages.subscriptionsTable(d.alerts().subscriptions()));
            } catch (IllegalArgumentException e) {
                html(x, Layout.notice(e.getMessage()) + Pages.subscriptionsTable(d.alerts().subscriptions()));
            }
        });
        post("/alerts/(alert|subscription)/(\\d{1,9})/(mute|remove)", (x, m) -> {
            long id = Long.parseLong(m.group(2));
            if (m.group(3).equals("mute")) {
                d.alerts().mute(m.group(1), id, Duration.ofDays(7));
            } else {
                d.alerts().remove(m.group(1), id);
            }
            html(x, m.group(1).equals("alert") ? Pages.alertsTable(d.alerts().alerts(), null) : Pages.subscriptionsTable(d.alerts().subscriptions()));
        });
        get("/rules", (x, m) -> html(x, page("Rule Catalog", "What each price rule means and when it fires.", Layout.Nav.RULES,
                Pages.rules(d.rules()))));
        transactionRoutes();
        get("/health", (x, m) -> send(x, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8)));
        get("/static/(.+)", (x, m) -> staticFile(x, m.group(1)));
    }

    // --- transactions (the only writes to the holdings source; always previewed, then confirmed) -----------

    private List<String> accounts() {
        try {
            return d.writer().accountNames();
        } catch (ca.twintigers.research.ports.DataSourceException e) {
            return List.of();
        }
    }

    private List<ca.twintigers.research.domain.portfolio.Activity> activities() throws ca.twintigers.research.ports.DataSourceException {
        return d.cockpit().transactions();
    }

    private ca.twintigers.research.domain.portfolio.Activity activity(String id) throws ca.twintigers.research.ports.DataSourceException {
        return activities().stream().filter(a -> id.equals(a.id())).findFirst()
                .orElseThrow(() -> new ca.twintigers.research.ports.DataSourceException("No transaction with id " + id + "."));
    }

    private String area(String panel, String error, String message) {
        List<ca.twintigers.research.domain.portfolio.Activity> list;
        try {
            list = activities();
        } catch (ca.twintigers.research.ports.DataSourceException e) {
            list = List.of();
            error = (error == null ? "" : error + " ") + e.getMessage();
        }
        return TransactionsPage.area(d.writer().name(), d.writer().enabled(), accounts(), list, panel, error, message);
    }

    private ca.twintigers.research.domain.portfolio.Account account(String name) throws ca.twintigers.research.ports.DataSourceException {
        return d.cockpit().accounts().stream().filter(a -> a.name().equals(name)).findFirst()
                .orElseThrow(() -> new ca.twintigers.research.ports.DataSourceException("No account named \"" + name + "\"."));
    }

    private List<ca.twintigers.research.domain.portfolio.Account> accountList(StringBuilder error) {
        try {
            return d.cockpit().accounts();
        } catch (ca.twintigers.research.ports.DataSourceException e) {
            error.append(e.getMessage());
            return List.of();
        }
    }

    private String cashCard(String panel, String error, String message) {
        StringBuilder err = new StringBuilder(error == null ? "" : error);
        var list = accountList(err);
        return CashPage.card(d.writer().name(), d.writer().enabled(), list, panel, err.isEmpty() ? null : err.toString(), message);
    }

    private String cashArea(String panel, String error, String message) {
        StringBuilder err = new StringBuilder(error == null ? "" : error);
        var list = accountList(err);
        return CashPage.area(d.writer().name(), d.writer().enabled(), list, panel, err.isEmpty() ? null : err.toString(), message);
    }

    private Map<String, ca.twintigers.research.domain.portfolio.HoldingReturns.Holding> returnsBySymbol() {
        Map<String, ca.twintigers.research.domain.portfolio.HoldingReturns.Holding> out = new java.util.LinkedHashMap<>();
        d.cockpit().holdingReturns().forEach(h -> out.put(h.symbol(), h));
        return out;
    }

    private String performanceBody() {
        var v = d.cockpit().performanceView();
        if (v.error() != null) {
            return PerformancePage.page(v);
        }
        var c = d.cockpit();
        java.math.BigDecimal zero = java.math.BigDecimal.ZERO;
        ca.twintigers.research.domain.portfolio.Portfolio p;
        try {
            p = c.portfolioView(20, 0).portfolio();
        } catch (RuntimeException e) {
            p = null;
        }
        String more = p == null ? "" : PortfolioScreens.moreAllocation(
                ca.twintigers.research.domain.portfolio.Allocation.bySector(p, c.profiles()),
                ca.twintigers.research.domain.portfolio.Allocation.byRegion(p, c.profiles()),
                ca.twintigers.research.domain.portfolio.Allocation.byCountry(p, c.profiles()),
                ca.twintigers.research.domain.portfolio.Allocation.byAssetClass(p, c.profiles()), c.estimatedProfiles(), v.baseCurrency());
        return PerformancePage.page(v) + "<div class=\"cockpit-grid\">" + PortfolioScreens.analysis(c.holdingReturns(), c.contributions(),
                v.baseCurrency()) + more + "</div>";
    }

    private Map<String, Object> performanceChart(String range) throws ca.twintigers.research.ports.DataSourceException {
        var r = d.cockpit().performanceSeries(range);
        Map<String, Object> out = PerformancePage.chart(r);
        List<Map<String, Object>> mine = new java.util.ArrayList<>();
        r.points().forEach(pt -> mine.add(Map.of("time", pt.date().toString(), "value", pt.returnPct().doubleValue())));
        out.put("returnPct", mine);
        List<Map<String, Object>> benches = new java.util.ArrayList<>();
        if (!r.points().isEmpty()) {
            for (String[] b : new String[][] {{"^GSPTSE", "S&P/TSX Composite"}, {"^GSPC", "S&P 500"}}) {
                List<Map<String, Object>> pts = new java.util.ArrayList<>();
                d.cockpit().benchmark(b[0], r.points().getFirst().date(), r.to())
                        .forEach(pt -> pts.add(Map.of("time", pt.date().toString(), "value", pt.returnPct().doubleValue())));
                benches.add(Map.of("name", b[1], "points", pts));
            }
        }
        out.put("benchmarks", benches);
        return out;
    }

    private static double dbl(Map<String, String> q, String k, double def) {
        try {
            String v = q.get(k);
            return v == null || v.isBlank() ? def : Double.parseDouble(v.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void transactionRoutes() {
        String id = "([A-Za-z0-9-]{1,64})";
        get("/transactions", (x, m) -> {
            String body;
            Map<String, String> q = query(x);
            var prefill = q.containsKey("type") ? TransactionsPage.parse(q).draft() : null;
            try {
                body = TransactionsPage.page(d.writer().name(), d.writer().enabled(), accounts(), activities(), null, prefill);
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                body = TransactionsPage.page(d.writer().name(), d.writer().enabled(), accounts(), List.of(), e.getMessage(), prefill);
            }
            html(x, page("Transactions", "Record and correct buys, sells, dividends and cash.", Layout.Nav.TRANSACTIONS,
                    cashCard(null, null, null) + body));
        });
        get("/accounts/cash-area", (x, m) -> html(x, cashArea(null, null, null)));
        get("/accounts/cash-edit", (x, m) -> {
            try {
                var a = account(query(x).getOrDefault("account", ""));
                html(x, cashArea(CashPage.form(a, a.cashBalance().toPlainString(), List.of()), null, null));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, cashArea(null, e.getMessage(), null));
            }
        });
        post("/accounts/cash-preview", (x, m) -> {
            Map<String, String> f = form(x);
            try {
                var a = account(f.getOrDefault("account", ""));
                List<String> problems = new java.util.ArrayList<>();
                java.math.BigDecimal cash = CashPage.parse(f.get("cash"), problems);
                html(x, cashArea(problems.isEmpty() ? CashPage.confirm(d.writer().name(), a, cash)
                        : CashPage.form(a, f.getOrDefault("cash", ""), problems), null, null));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, cashArea(null, e.getMessage(), null));
            }
        });
        post("/accounts/cash-save", (x, m) -> {
            Map<String, String> f = form(x);
            try {
                var before = account(f.getOrDefault("account", ""));
                List<String> problems = new java.util.ArrayList<>();
                java.math.BigDecimal cash = CashPage.parse(f.get("cash"), problems);
                if (!problems.isEmpty()) {
                    throw new ca.twintigers.research.ports.DataSourceException(String.join(" ", problems));
                }
                var after = d.writer().setCash(before.name(), cash);
                html(x, cashArea(null, null, "Saved: cash in " + after.name() + " is now " + Html.money(after.cashBalance()) + " "
                        + after.currency() + " (was " + Html.money(before.cashBalance()) + "; " + d.writer().name() + ")."));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, cashArea(null, e.getMessage(), null));
            }
        });
        get("/accounts/new", (x, m) -> html(x, cashArea(CashPage.newAccountForm("", "CAD", "0", List.of()), null, null)));
        post("/accounts/new", (x, m) -> {
            Map<String, String> f = form(x);
            List<String> problems = new java.util.ArrayList<>();
            java.math.BigDecimal cash = CashPage.parse(f.getOrDefault("cash", "0").isBlank() ? "0" : f.get("cash"), problems);
            try {
                if (!problems.isEmpty()) {
                    throw new ca.twintigers.research.ports.DataSourceException(String.join(" ", problems));
                }
                var a = d.writer().addAccount(f.get("name"), f.get("currency"), cash);
                html(x, cashArea(null, null, "Added the account " + a.name() + " (" + a.currency() + ", cash " + Html.money(a.cashBalance()) + ")."));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, cashArea(CashPage.newAccountForm(f.getOrDefault("name", ""), f.getOrDefault("currency", ""), f.getOrDefault("cash", ""),
                        List.of(e.getMessage())), null, null));
            }
        });
        get("/accounts/transfer", (x, m) -> {
            StringBuilder err = new StringBuilder();
            var list = accountList(err);
            html(x, cashArea(CashPage.transferForm(list, list.isEmpty() ? null : list.get(0).name(),
                    list.size() < 2 ? null : list.get(1).name(), "", "", List.of()), null, null));
        });
        post("/accounts/transfer-preview", (x, m) -> {
            Map<String, String> f = form(x);
            try {
                var from = account(f.getOrDefault("from", ""));
                var to = account(f.getOrDefault("to", ""));
                List<String> problems = new java.util.ArrayList<>();
                var t = CashPage.transfer(from, to, f.get("amount"), f.get("received"), d.cockpit().fxRate(from.currency(), to.currency()), problems);
                StringBuilder err = new StringBuilder();
                html(x, cashArea(t != null ? CashPage.transferConfirm(d.writer().name(), t)
                        : CashPage.transferForm(accountList(err), from.name(), to.name(), f.getOrDefault("amount", ""), f.getOrDefault("received", ""),
                        problems), null, null));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, cashArea(null, e.getMessage(), null));
            }
        });
        post("/accounts/transfer-save", (x, m) -> {
            Map<String, String> f = form(x);
            String step = "checking the accounts";
            try {
                var from = account(f.getOrDefault("from", ""));
                var to = account(f.getOrDefault("to", ""));
                List<String> problems = new java.util.ArrayList<>();
                // the confirmed amounts (received as shown in the preview), checked again against today's balances
                var t = CashPage.transfer(from, to, f.get("amount"), f.getOrDefault("received", f.get("amount")), null, problems);
                if (t == null) {
                    throw new ca.twintigers.research.ports.DataSourceException(String.join(" ", problems));
                }
                step = "taking the cash out of " + from.name();
                d.writer().setCash(from.name(), from.cashBalance().subtract(t.amount()));
                step = "adding it to " + to.name() + " (" + from.name() + " was already reduced by " + Html.money(t.amount()) + " "
                        + from.currency() + "; add " + Html.money(t.received()) + " " + to.currency() + " to " + to.name() + " with Change cash)";
                var after = d.writer().setCash(to.name(), to.cashBalance().add(t.received()));
                html(x, cashArea(null, null, "Moved " + Html.money(t.amount()) + " " + from.currency() + " from " + from.name() + " to "
                        + to.name() + (t.converted() ? " (arrived as " + Html.money(t.received()) + " " + to.currency() + ")" : "") + "; "
                        + to.name() + " now has " + Html.money(after.cashBalance()) + " " + after.currency() + " (" + d.writer().name() + ")."));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, cashArea(null, "Stopped while " + step + ": " + e.getMessage(), null));
            }
        });
        get("/transactions/area", (x, m) -> html(x, area(null, null, null)));
        get("/transactions/" + id + "/edit", (x, m) -> {
            try {
                var a = activity(m.group(1));
                var draft = new ca.twintigers.research.domain.portfolio.ActivityDraft(a.type(), a.account(), a.symbol(), a.date(), a.quantity(),
                        a.unitPrice(), a.fee(), a.currency(), a.comment());
                html(x, area(TransactionsPage.form(a.id(), draft, accounts(), List.of(), "Edit transaction"), null, null));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, area(null, e.getMessage(), null));
            }
        });
        post("/transactions/preview", (x, m) -> {
            TransactionsPage.Form f = TransactionsPage.parse(form(x));
            List<String> problems = new java.util.ArrayList<>(f.problems());
            problems.addAll(f.draft().problems(LocalDate.now(java.time.ZoneId.of("America/Toronto")), accounts()));
            String title = f.id() == null ? "Add a transaction" : "Edit transaction";
            if (!problems.isEmpty()) {
                html(x, area(TransactionsPage.form(f.id(), f.draft(), accounts(), problems.stream().distinct().toList(), title), null, null));
                return;
            }
            try {
                var before = f.id() == null ? null : activity(f.id());
                html(x, area(TransactionsPage.confirm(d.writer().name(), f.id() == null ? "create" : "update", f.id(), f.draft(), before), null, null));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, area(null, e.getMessage(), null));
            }
        });
        post("/transactions/save", (x, m) -> {
            Map<String, String> raw = form(x);
            TransactionsPage.Form f = TransactionsPage.parse(raw);
            try {
                if (!f.problems().isEmpty()) {
                    throw new ca.twintigers.research.ports.DataSourceException(String.join(" ", f.problems()));
                }
                var saved = "update".equals(raw.get("op")) && f.id() != null ? d.writer().update(f.id(), f.draft()) : d.writer().create(f.draft());
                html(x, area(null, null, ("update".equals(raw.get("op")) ? "Saved: " : "Added: ") + TransactionsPage.describe(saved)
                        + " (" + d.writer().name() + ")."));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, area(TransactionsPage.form(f.id(), f.draft(), accounts(), List.of(e.getMessage()),
                        f.id() == null ? "Add a transaction" : "Edit transaction"), null, null));
            }
        });
        post("/transactions/" + id + "/delete-preview", (x, m) -> {
            try {
                var a = activity(m.group(1));
                html(x, area(TransactionsPage.confirm(d.writer().name(), "delete", a.id(), null, a), null, null));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, area(null, e.getMessage(), null));
            }
        });
        post("/transactions/" + id + "/delete", (x, m) -> {
            try {
                var a = activity(m.group(1));
                d.writer().delete(a.id());
                html(x, area(null, null, "Deleted: " + TransactionsPage.describe(a) + " (" + d.writer().name() + ")."));
            } catch (ca.twintigers.research.ports.DataSourceException e) {
                html(x, area(null, e.getMessage(), null));
            }
        });
    }

    private static Double num(Map<String, String> f, String k) {
        String v = f.get(k);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(k + " must be a number.");
        }
    }

    // --- dispatch and safety ------------------------------------------------------------------------------

    private boolean localHost(HttpExchange x) {
        String host = x.getRequestHeaders().getFirst("Host");
        return host != null && (host.equals("localhost:" + port) || host.equals("127.0.0.1:" + port));
    }

    private boolean postAllowed(HttpExchange x) {
        String origin = x.getRequestHeaders().getFirst("Origin");
        boolean originOk = origin == null || origin.equals("http://localhost:" + port) || origin.equals("http://127.0.0.1:" + port);
        return originOk && csrf.equals(x.getRequestHeaders().getFirst("X-CSRF-Token"));
    }

    private void dispatch(HttpExchange x) throws IOException {
        try {
            security(x);
            if (!localHost(x)) {
                send(x, 403, "text/plain; charset=utf-8", "Forbidden: the Cockpit answers only http://localhost:8099".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String method = x.getRequestMethod();
            if (method.equals("POST") && !postAllowed(x)) {
                send(x, 403, "text/plain; charset=utf-8", "Forbidden: missing or wrong request token.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = x.getRequestURI().getPath();
            for (Route r : routes) {
                Matcher m = r.path().matcher(path);
                if (r.method().equals(method) && m.matches()) {
                    r.handler().handle(x, m);
                    return;
                }
            }
            send(x, 404, "text/html; charset=utf-8", page("Not found", path, Layout.Nav.PORTFOLIO,
                    Layout.notice("There is no page at " + path + ".")).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            byte[] body = ("Something went wrong: " + e.getClass().getSimpleName() + ": " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            try {
                send(x, 500, "text/plain; charset=utf-8", body);
            } catch (IOException ignored) {
                // client went away
            }
        } finally {
            x.close();
        }
    }

    private static void security(HttpExchange x) {
        var h = x.getResponseHeaders();
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
    }

    private void staticFile(HttpExchange x, String rel) throws IOException {
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
            send(x, 404, "text/plain", new byte[0]);
            return;
        }
        try (InputStream in = CockpitServer.class.getResourceAsStream("/static/" + rel)) {
            if (in == null) {
                send(x, 404, "text/plain", new byte[0]);
                return;
            }
            String type = rel.endsWith(".css") ? "text/css; charset=utf-8" : rel.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : rel.endsWith(".svg") ? "image/svg+xml" : rel.endsWith(".png") ? "image/png" : "text/plain; charset=utf-8";
            x.getResponseHeaders().set("Cache-Control", "max-age=3600");
            send(x, 200, type, in.readAllBytes());
        }
    }

    private static void html(HttpExchange x, String body) throws IOException {
        send(x, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void redirect(HttpExchange x, String to) throws IOException {
        x.getResponseHeaders().set("Location", to);
        send(x, 303, "text/plain", new byte[0]);
    }

    private static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream o = x.getResponseBody()) {
                o.write(body);
            }
        }
    }

    private static Map<String, String> query(HttpExchange x) {
        return decode(x.getRequestURI().getRawQuery());
    }

    private static Map<String, String> form(HttpExchange x) throws IOException {
        byte[] b = x.getRequestBody().readNBytes(16_384);
        return decode(new String(b, StandardCharsets.UTF_8));
    }

    static Map<String, String> decode(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
