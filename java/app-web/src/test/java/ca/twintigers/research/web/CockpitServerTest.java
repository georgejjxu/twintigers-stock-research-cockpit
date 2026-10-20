package ca.twintigers.research.web;

import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.adapters.store.SqliteAlertDb;
import ca.twintigers.research.adapters.store.SqliteWatchlist;
import ca.twintigers.research.application.AlertService;
import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.application.FinancialHealthService;
import ca.twintigers.research.application.PriceSignalService;
import ca.twintigers.research.domain.fundamentals.Thresholds;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.PriceRuleConfig;
import ca.twintigers.research.domain.market.PriceRules;
import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The Cockpit server end to end over HTTP, on a random port, with real SQLite stores and fake data sources. */
class CockpitServerTest {

    @TempDir
    Path dir;
    CockpitServer server;
    HttpClient http = HttpClient.newHttpClient();
    String base;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T20:30:00Z"), ZoneId.of("America/Toronto"));

    static final MarketData MARKET = new MarketData() {
        @Override
        public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) {
            List<Bar> out = new ArrayList<>();
            for (int i = 120; i >= 0; i--) {
                double c = 60 + (i % 10) * 0.5;
                out.add(new Bar(LocalDate.of(2026, 10, 19).minusDays(i), c, c + 0.6, c - 0.6, c + (i % 2 == 0 ? 0.3 : -0.3), 1_000_000));
            }
            return out;
        }

        @Override
        public String source() {
            return "test bars";
        }
    };

    /** In-memory source and writer: holdings fixed, transactions in a list. */
    static final class FakeSource implements PortfolioSource, ca.twintigers.research.ports.PortfolioWriter {
        final List<Activity> activities = new ArrayList<>();
        boolean enabled = true;
        int writes;
        int next = 1;
        BigDecimal rrspCash = BigDecimal.ZERO;

        @Override
        public String name() {
            return "test-source";
        }

        @Override
        public Set<Capability> capabilities() {
            return EnumSet.of(Capability.ACCOUNTS, Capability.ACTIVITIES);
        }

        @Override
        public Portfolio portfolio() {
            return new Portfolio("CAD", List.of(new Portfolio.Position("ENB.TO", "Enbridge <Inc>", "CAD", new BigDecimal("2080"),
                    new BigDecimal("68.04"), new BigDecimal("141523.20"), Map.of("RRSP", new BigDecimal("980"), "Cash CAD", new BigDecimal("1100")))),
                    new BigDecimal("400000"));
        }

        @Override
        public List<Account> accounts() {
            return List.of(new Account("RRSP", "CAD", rrspCash, rrspCash));
        }

        @Override
        public List<Activity> activities() {
            return List.copyOf(activities);
        }

        @Override
        public Optional<Performance> performance(String range) {
            return Optional.empty();
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<String> accountNames() {
            return List.of("Cash CAD", "RRSP");
        }

        private Activity of(String id, ca.twintigers.research.domain.portfolio.ActivityDraft d) throws DataSourceException {
            if (!enabled) {
                throw new DataSourceException("Writing is switched off.");
            }
            var x = d.normalized();
            var problems = x.problems(LocalDate.of(2026, 10, 19), accountNames());
            if (!problems.isEmpty()) {
                throw new DataSourceException(String.join(" ", problems));
            }
            return new Activity(id, x.date(), x.type(), x.account(), x.symbol(), x.quantity(), x.unitPrice(), x.fee(), x.currency(), x.comment());
        }

        @Override
        public Activity create(ca.twintigers.research.domain.portfolio.ActivityDraft d) throws DataSourceException {
            Activity a = of(String.valueOf(next++), d);
            activities.add(a);
            writes++;
            return a;
        }

        @Override
        public Activity update(String id, ca.twintigers.research.domain.portfolio.ActivityDraft d) throws DataSourceException {
            Activity a = of(id, d);
            activities.replaceAll(x -> x.id().equals(id) ? a : x);
            writes++;
            return a;
        }

        @Override
        public void delete(String id) throws DataSourceException {
            if (!enabled) {
                throw new DataSourceException("Writing is switched off.");
            }
            activities.removeIf(x -> x.id().equals(id));
            writes++;
        }

        @Override
        public Account setCash(String account, BigDecimal cash) throws DataSourceException {
            if (!enabled) {
                throw new DataSourceException("Writing is switched off.");
            }
            ca.twintigers.research.ports.PortfolioWriter.checkCash(account, cash, List.of("RRSP"));
            rrspCash = cash;
            writes++;
            return accounts().getFirst();
        }
    }

    FakeSource source;

    @BeforeEach
    void start() throws Exception {
        Database db = new Database(dir.resolve("research.db"));
        SqliteAlertDb alertDb = new SqliteAlertDb(db);
        PriceSignalService prices = new PriceSignalService(MARKET, PriceRuleConfig.defaults(), CLOCK);
        FinancialHealthService health = new FinancialHealthService(t -> Optional.empty(), cik -> {
            throw new DataSourceException("no SEC in this test");
        }, Thresholds.defaults());
        source = new FakeSource();
        CockpitService cockpit = new CockpitService(source, new SqliteWatchlist(db, CLOCK), alertDb, MARKET, prices, health,
                Map.of("ENB", "ENB"), CLOCK);
        AlertService alerts = new AlertService(alertDb, PriceRules.all(PriceRuleConfig.defaults()), CLOCK);
        server = new CockpitServer(new CockpitServer.Deps(cockpit, alerts, () -> List.of("toast"), () -> alertDb.recentRuns(5),
                () -> alertDb.recentEntries(5), prices.engine().rules(), source), 0);
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String form, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (token != null) {
            b.header("X-CSRF-Token", token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void portfolioPageUsesTheTwintigerShellAndEscapesData() throws Exception {
        var r = get("/");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("class=\"app-shell\"", "class=\"app-sidebar\"", "class=\"sidebar-link active\"",
                "/static/vendor/twintiger/dashboard.css", "class=\"kpi accent\"", "Net worth", "541,523.20 CAD", "test-source",
                "Enbridge &lt;Inc&gt;", "RRSP 980");
        assertThat(r.body()).doesNotContain("Enbridge <Inc>");
        assertThat(r.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v -> assertThat(v).contains("script-src 'self'"));
        assertThat(r.headers().firstValue("X-Frame-Options")).contains("DENY");
    }

    @Test
    void everyPageRenders() throws Exception {
        for (String p : List.of("/watchlist", "/alerts", "/rules", "/stock/ENB.TO")) {
            var r = get(p);
            assertThat(r.statusCode()).as(p).isEqualTo(200);
            assertThat(r.body()).as(p).contains("app-shell", "Stock Research Cockpit");
        }
        assertThat(get("/rules").body()).contains("PA02_seller_controlled_drop", "K05_dark_cloud_cover");
        assertThat(get("/stock/ENB.TO").body()).contains("id=\"chart\"", "data-symbol=\"ENB.TO\"", "ATR (14)");
        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void stockPagePicksTheSymbolFromHoldingsAndTheWatchList() throws Exception {
        String held = Pages.symbolOptions("ENB.TO", List.of("IBM", "ENB.TO"), List.of("RY.TO", "IBM"));
        assertThat(held).isEqualTo("<optgroup label=\"Holdings\"><option selected>ENB.TO</option><option>IBM</option></optgroup>"
                + "<optgroup label=\"Watch list\"><option>RY.TO</option></optgroup>");
        assertThat(Pages.symbolOptions("SHOP.TO", List.of("IBM"), List.of())).startsWith("<option selected>SHOP.TO</option>");

        assertThat(get("/stock/ENB.TO").body()).contains("<select name=\"symbol\" id=\"symbol-select\">", "name=\"other\"",
                "list=\"symbol-suggestions\"");
        assertThat(Pages.symbolSuggestions(List.of(new ca.twintigers.research.ports.SymbolSearch.Match("RY.TO", "Royal Bank of Canada",
                "Toronto", "Stock"), new ca.twintigers.research.ports.SymbolSearch.Match("XIU.TO", "iShares <S&P/TSX 60>", "Toronto", "ETF"))))
                .isEqualTo("<option value=\"RY.TO\">Royal Bank of Canada · Toronto</option>"
                        + "<option value=\"XIU.TO\">iShares &lt;S&amp;P/TSX 60&gt; · Toronto · ETF</option>");
        assertThat(get("/symbols/suggest?other=r").body()).isEmpty(); // too short to search
        // "another symbol" wins over the drop-down, lower case accepted
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/stock?symbol=ENB.TO&other=ry.to")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(r.headers().firstValue("Location")).hasValue("/stock/RY.TO");
    }

    @Test
    void onlyLocalHostNamesAreAnswered() throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        // A request for another host name (DNS rebinding) is refused. java.net.http does not let us forge Host, so use a raw socket.
        try (var s = new java.net.Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n".getBytes());
            String status = new String(s.getInputStream().readNBytes(12));
            assertThat(status).isEqualTo("HTTP/1.1 403");
        }
    }

    @Test
    void writesNeedTheTokenAndALocalOrigin() throws Exception {
        assertThat(post("/watchlist", "symbol=IBM", null).statusCode()).isEqualTo(403);
        assertThat(post("/watchlist", "symbol=IBM", "wrong").statusCode()).isEqualTo(403);
        var bad = http.send(HttpRequest.newBuilder(URI.create(base + "/watchlist")).header("X-CSRF-Token", server.csrfToken())
                .header("Origin", "https://attacker.example").POST(HttpRequest.BodyPublishers.ofString("symbol=IBM")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(403);

        var ok = post("/watchlist", "symbol=ibm&note=AI+play", server.csrfToken());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("Added IBM.", "AI play", "/watchlist/IBM/remove");
        assertThat(post("/watchlist", "symbol=%3Cscript%3E", server.csrfToken()).body()).contains("Not a valid symbol");
        assertThat(post("/watchlist/IBM/remove", "", server.csrfToken()).body()).contains("Removed IBM.");
    }

    @Test
    void alertsCanBeAddedMutedAndRemovedFromTheUi() throws Exception {
        var add = post("/alerts/threshold", "symbol=ENB.TO&kind=below&level=60&note=buy-watch", server.csrfToken());
        assertThat(add.body()).contains("Added alert #1", "ENB.TO at or below 60.00", "armed");
        assertThat(post("/alerts/threshold", "symbol=ENB.TO&kind=above", server.csrfToken()).body()).contains("level (a price &gt; 0) is required");
        assertThat(post("/alerts/alert/1/mute", "", server.csrfToken()).body()).contains("muted until");
        assertThat(post("/alerts/alert/1/remove", "", server.csrfToken()).body()).contains("No price alerts yet.");
        var sub = post("/alerts/subscription", "scope=*&source=price&rule=PA02_seller_controlled_drop&minSeverity=warning", server.csrfToken());
        assertThat(sub.body()).contains("PA02_seller_controlled_drop", "all holdings", "user");
    }

    @Test
    void chartJsonHasCandlesVolumeAverageMarkersAndAlertLines() throws Exception {
        post("/alerts/threshold", "symbol=ENB.TO&kind=above&level=75", server.csrfToken());
        var r = get("/api/chart/ENB.TO");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        var json = new tools.jackson.databind.ObjectMapper().readTree(r.body());
        assertThat(json.path("candles").size()).isEqualTo(121);
        assertThat(json.path("candles").path(0).path("time").asString()).isEqualTo("2026-06-21");
        assertThat(json.path("volume").size()).isEqualTo(121);
        assertThat(json.path("mode").asString()).isEqualTo("daily");
        assertThat(json.path("interval").asString()).isEqualTo("1d");
        assertThat(json.path("sma50").size()).isEqualTo(121 - 49);
        assertThat(json.path("sma200").size()).isZero(); // only 121 bars of history in this test
        assertThat(json.path("markers").isArray()).isTrue();
        assertThat(json.path("alertLines").path(0).path("price").asDouble()).isEqualTo(75.0);
        assertThat(json.path("alertLines").path(0).path("title").asString()).isEqualTo("#1 above");
        assertThat(json.path("colors").path("up").asString()).isEqualTo("#167b73");
    }

    static final String TX = "type=BUY&account=RRSP&symbol=fts.to&date=2026-03-02&quantity=100&unitPrice=60.25&fee=4.95&currency=CAD&comment=real+price";

    @Test
    void intradayChartDataIsInExchangeTimeWithVwapAndAlerts() throws Exception {
        var zone = java.time.ZoneId.of("America/Toronto");
        var open = java.time.Instant.parse("2026-10-19T13:30:00Z"); // 09:30 in Toronto (EDT, UTC-4)
        var series = new ca.twintigers.research.ports.IntradayData.Series("ENB.TO", ca.twintigers.research.domain.market.Interval.M5, zone,
                List.of(new ca.twintigers.research.domain.market.Candle(open, 67, 67.5, 66.5, 67.2, 1000)), "test feed", 15,
                open.plusSeconds(600));
        Map<String, Object> d = ChartData.intraday(series, List.of());
        @SuppressWarnings("unchecked")
        var first = ((List<Map<String, Object>>) d.get("candles")).getFirst();
        assertThat(first.get("time")).isEqualTo(open.getEpochSecond() - 4 * 3600); // the axis shows 09:30
        assertThat(d).containsEntry("lastBar", "2026-10-19 09:30").containsEntry("live", true).containsEntry("interval", "5m");
        assertThat((List<?>) d.get("vwap")).hasSize(1);

        assertThat(get("/stock/ENB.TO").body()).contains("data-range=\"1D\"", "data-range=\"MAX\"", "id=\"qc-dates\"",
                "data-type=\"area\"", "id=\"qc-sma200\"", "id=\"qc-volume\"", "id=\"qc-log\"", "data-zoom=\"reset\"",
                "id=\"qc-overview\"", "id=\"qc-readout\"", "/static/quote-chart.js?v=");
        assertThat(get("/api/chart/ENB.TO?range=1D").body()).contains("No intraday data source"); // none in this test
        assertThat(get("/api/chart/ENB.TO?range=7Y").body()).contains("Range must be one of");
        var r = get("/api/intraday/ENB.TO?interval=5m"); // no intraday source in this test
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains("No intraday data source");
        assertThat(get("/api/intraday/ENB.TO?interval=2d").body()).contains("Interval must be");
    }

    @Test
    void movingCashChecksTheAmountsAndConvertsCurrencies() {
        Account cad = new Account("Cash CAD", "CAD", new BigDecimal("1000"), new BigDecimal("1000"));
        Account rrsp = new Account("RRSP", "CAD", new BigDecimal("50"), new BigDecimal("50"));
        Account usd = new Account("Cash USD", "USD", new BigDecimal("200"), new BigDecimal("280"));
        List<String> p = new java.util.ArrayList<>();
        var same = CashPage.transfer(cad, rrsp, "250.50", "", 1.0, p);
        assertThat(p).isEmpty();
        assertThat(same.received()).isEqualByComparingTo("250.50");
        assertThat(CashPage.transferConfirm("test-source", same)).contains("move 250.50 CAD from Cash CAD to RRSP", "749.50 CAD", "300.50 CAD");

        var converted = CashPage.transfer(usd, cad, "100", "", 1.4, p);
        assertThat(converted.received()).isEqualByComparingTo("140.00");
        assertThat(CashPage.transferConfirm("test-source", converted)).contains("Arrives as 140.00 CAD", "1.4000 CAD per USD");
        assertThat(CashPage.transfer(usd, cad, "100", "139.10", 1.4, p).received()).isEqualByComparingTo("139.10"); // as typed

        assertThat(CashPage.transfer(rrsp, cad, "60", "", 1.0, p)).isNull();
        assertThat(p).containsExactly("RRSP has only 50.00 CAD.");
        p.clear();
        assertThat(CashPage.transfer(cad, cad, "1", "", 1.0, p)).isNull();
        assertThat(p).containsExactly("Choose two different accounts.");
        p.clear();
        assertThat(CashPage.transfer(usd, cad, "10", "", null, p)).isNull();
        assertThat(p.getFirst()).contains("No exchange rate");
    }

    @Test
    void xrayFireAndPositionScreens() throws Exception {
        assertThat(get("/xray").body()).contains("Portfolio checks", "Largest single company", "ENB is", "not set up", "data-icon=\"audit\"");
        var fire = get("/fire?savings=50000&return=5&rate=4&spending=60000&years=10");
        assertThat(fire.body()).contains("Financial independence", "Withdrawal per year", "Net worth needed", "1,500,000.00 CAD");
        assertThat(get("/fire?years=abc").statusCode()).isEqualTo(200); // bad input falls back to defaults
        assertThat(get("/performance").body()).contains("Compared with the market", "Best and worst holdings", "Money put in by month",
                "By sector", "By region", "By asset class");
        String pos = PortfolioScreens.position("X", java.util.Optional.empty(),
                List.of(new Activity("1", LocalDate.of(2026, 3, 2), "BUY", "RRSP", "X", new BigDecimal("10"), new BigDecimal("100"),
                        BigDecimal.ZERO, "CAD", "first")), "CAD");
        assertThat(pos).contains("Your position", "first", "Edit these on the Transactions page");
        assertThat(PortfolioScreens.position("Y", java.util.Optional.empty(), List.of(), "CAD")).isEmpty();
    }

    @Test
    void performancePageAndDividendPrefill() throws Exception {
        var page = get("/performance");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Value over time", "id=\"perf-chart\"", "By holding", "By currency", "By account",
                "Income by month", "Paid but not recorded", "data-icon=\"history\"");
        String link = PerformancePage.missing(List.of(new ca.twintigers.research.domain.portfolio.Dividends.Missing("RRSP", "ENB.TO",
                java.time.LocalDate.of(2026, 8, 14), new BigDecimal("980"), new BigDecimal("0.9425"), "CAD")));
        assertThat(link).contains("href=\"/transactions?type=DIVIDEND&amp;account=RRSP&amp;symbol=ENB.TO&amp;date=2026-08-14&amp;"
                + "quantity=980&amp;unitPrice=0.9425&amp;currency=CAD\"", "923.65 CAD");
        var prefilled = get("/transactions?type=DIVIDEND&account=RRSP&symbol=ENB.TO&date=2026-08-14&quantity=980&unitPrice=0.9425&currency=CAD");
        assertThat(prefilled.body()).contains("Record a dividend", "value=\"0.9425\"", "<option value=\"DIVIDEND\" selected>");
        assertThat(source.writes).isZero(); // prefilling writes nothing
    }

    @Test
    void cashIsPreviewedAndOnlyWrittenOnConfirm() throws Exception {
        String t = server.csrfToken();
        assertThat(get("/transactions").body()).contains("Accounts and cash", "Change cash", "id=\"cash-area\"");
        assertThat(get("/accounts/cash-edit?account=RRSP").body()).contains("Change the cash in RRSP", "New cash balance (CAD)");

        assertThat(post("/accounts/cash-preview", "account=RRSP&cash=-5", t).body()).contains("Cash must be 0 or more.");
        var preview = post("/accounts/cash-preview", "account=RRSP&cash=1500.25", t);
        assertThat(preview.body()).contains("Please confirm: change the cash in RRSP in test-source", "0.00 CAD", "1,500.25 CAD");
        assertThat(source.writes).isZero();

        var saved = post("/accounts/cash-save", "account=RRSP&cash=1500.25", t);
        assertThat(saved.body()).contains("Saved: cash in RRSP is now 1,500.25 CAD (was 0.00");
        assertThat(source.rrspCash).isEqualByComparingTo("1500.25");
        assertThat(post("/accounts/cash-save", "account=Nope&cash=1", t).body()).contains("No account named");
        assertThat(post("/accounts/cash-save", "account=RRSP&cash=1", null).statusCode()).isEqualTo(403); // no CSRF token
    }

    @Test
    void transactionsArePreviewedAndOnlyWrittenOnConfirm() throws Exception {
        String t = server.csrfToken();
        var page = get("/transactions");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Add a transaction", "<option>RRSP</option>", "No transactions recorded.");

        var preview = post("/transactions/preview", TX, t);
        assertThat(preview.body()).contains("Please confirm: add this transaction to test-source",
                "BUY 100 FTS.TO @ 60.25 CAD on 2026-03-02 in RRSP (fee 4.95)", "hx-post=\"/transactions/save\"");
        assertThat(source.writes).isZero();

        var saved = post("/transactions/save", "op=create&" + TX, t);
        assertThat(saved.body()).contains("Added: BUY 100 FTS.TO @ 60.25 CAD on 2026-03-02 in RRSP", "FTS.TO");
        assertThat(source.activities).hasSize(1);

        var edit = get("/transactions/1/edit");
        assertThat(edit.body()).contains("Edit transaction", "value=\"60.25\"", "name=\"id\" value=\"1\"");
        var editPreview = post("/transactions/preview", "id=1&" + TX.replace("60.25", "61.10"), t);
        assertThat(editPreview.body()).contains("change this transaction", "Now:", "@ 60.25", "After:", "@ 61.1 CAD");
        post("/transactions/save", "op=update&id=1&" + TX.replace("60.25", "61.10"), t);
        assertThat(source.activities.getFirst().unitPrice()).isEqualByComparingTo("61.10");

        assertThat(post("/transactions/1/delete-preview", "", t).body()).contains("delete this transaction", "danger-button");
        assertThat(source.activities).hasSize(1);
        assertThat(post("/transactions/1/delete", "", t).body()).contains("Deleted: BUY 100 FTS.TO");
        assertThat(source.activities).isEmpty();
    }

    @Test
    void invalidTransactionsShowTheirProblemsAndWriteNothing() throws Exception {
        var r = post("/transactions/preview", "type=BUY&account=TFSA&symbol=%3Cb%3E&date=2030-01-01&quantity=abc&unitPrice=-1&currency=EUR",
                server.csrfToken());
        assertThat(r.body()).contains("Shares must be a number.", "Unknown account &quot;TFSA&quot;", "in the future",
                "Price must be 0 or more.", "Currency must be CAD or USD.").doesNotContain("<b>");
        assertThat(source.writes).isZero();
        assertThat(post("/transactions/save", "op=create&" + TX, null).statusCode()).isEqualTo(403); // no token
        assertThat(source.writes).isZero();
    }

    @Test
    void placeholderBuysOfferEnteringTheRealPriceAndASwitchedOffWriterShowsWhy() throws Exception {
        source.activities.add(new Activity("7", LocalDate.of(2026, 10, 14), "BUY", "RRSP", "ENB.TO", new BigDecimal("980"),
                new BigDecimal("67.5"), BigDecimal.ZERO, "CAD", "Placeholder cost = 2026-10-14 close"));
        assertThat(get("/transactions").body()).contains("placeholder price", "Enter real price", "/transactions/7/edit");
        source.enabled = false;
        var off = get("/transactions").body();
        assertThat(off).contains("switched off", "GHOSTFOLIO_WRITES=enabled").doesNotContain("Add a transaction", "/transactions/7/edit");
        assertThat(post("/transactions/save", "op=create&" + TX, server.csrfToken()).body()).contains("Writing is switched off.");
        assertThat(source.activities).hasSize(1);
    }

    @Test
    void staticFilesAreServedButNotOutsideTheStaticFolder() throws Exception {
        assertThat(get("/static/vendor/twintiger/dashboard.css").body()).contains("--teal: #167b73");
        assertThat(get("/static/vendor/lightweight-charts/lightweight-charts-5.2.1.standalone.production.js").statusCode()).isEqualTo(200);
        assertThat(get("/static/../db/migration/V1__bars.sql").statusCode()).isEqualTo(404);
        assertThat(get("/static/%2e%2e/db/migration/V1__bars.sql").statusCode()).isEqualTo(404);
    }
}
