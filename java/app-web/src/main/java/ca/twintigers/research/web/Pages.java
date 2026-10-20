package ca.twintigers.research.web;

import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.application.CockpitService.Health;
import ca.twintigers.research.application.CockpitService.Signals;
import ca.twintigers.research.application.HealthMarkdown;
import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.alerts.Subscription;
import ca.twintigers.research.domain.fundamentals.HealthTable;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.domain.provenance.Cell;
import ca.twintigers.research.domain.provenance.Fact;
import ca.twintigers.research.domain.rules.Flag;
import ca.twintigers.research.domain.rules.Severity;
import ca.twintigers.research.ports.Outbox;
import ca.twintigers.research.ports.RunLog;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import static ca.twintigers.research.web.Html.e;
import static ca.twintigers.research.web.Layout.badge;
import static ca.twintigers.research.web.Layout.card;
import static ca.twintigers.research.web.Layout.kpi;

/** Page bodies. Numbers come from the view records; this class only formats them. */
final class Pages {

    private Pages() {
    }

    static final ZoneId ET = ZoneId.of("America/Toronto");

    static String severityBadge(Severity s) {
        return badge(s.label(), switch (s) {
            case WARNING -> "danger";
            case WATCH -> "watch";
            case INFO -> "success";
        });
    }

    static String healthCell(Health h) {
        if (h == null) {
            return "–";
        }
        if (h.unavailable() != null) {
            return "<span class=\"muted\" title=\"" + e(h.unavailable()) + "\">n/a</span>";
        }
        if (h.flags().isEmpty()) {
            return badge("no flags", "success");
        }
        StringBuilder b = new StringBuilder();
        for (Flag f : h.flags()) {
            b.append("<span title=\"").append(e(f.ruleId() + ": " + f.text())).append("\">").append(severityBadge(f.severity()))
             .append("</span> ");
        }
        return b.toString();
    }

    static String signalsCell(Signals s) {
        if (s == null) {
            return "–";
        }
        if (s.unavailable() != null) {
            return "<span class=\"muted\" title=\"" + e(s.unavailable()) + "\">n/a</span>";
        }
        if (s.signals().isEmpty()) {
            return "<span class=\"muted\">none</span>";
        }
        long w = s.count(Severity.WARNING);
        long v = s.count(Severity.WATCH);
        Signal latest = s.signals().getLast();
        return (w > 0 ? badge(w + " warning", "danger") + " " : "") + (v > 0 ? badge(v + " watch", "watch") + " " : "")
                + "<span class=\"muted\" title=\"" + e(latest.text()) + "\">last: " + e(latest.date()) + " " + e(latest.ruleName()) + "</span>";
    }

    static String symbolLink(String symbol) {
        return "<a class=\"cockpit-symbol\" href=\"/stock/" + e(Html.sym(symbol)) + "\">" + e(symbol) + "</a>";
    }

    // --- Portfolio ---------------------------------------------------------------------------------------

    static String portfolio(CockpitService.PortfolioView v) {
        return portfolio(v, Map.of());
    }

    /** @param returns per-symbol profit and loss (average cost, gain) from the transactions; empty hides those columns */
    static String portfolio(CockpitService.PortfolioView v,
                            Map<String, ca.twintigers.research.domain.portfolio.HoldingReturns.Holding> returns) {
        if (v.error() != null) {
            return Layout.notice("The holdings source (" + v.source() + ") is not available: " + v.error());
        }
        Portfolio p = v.portfolio();
        String cur = p.baseCurrency();
        StringBuilder out = new StringBuilder();
        Portfolio.IssuerWeight top = v.issuers().isEmpty() ? null : v.issuers().getFirst();
        out.append("<section class=\"context-bar page-context-bar\" aria-label=\"Portfolio context\"><span>")
           .append(p.positions().size()).append(" positions · ").append(v.accounts().size()).append(" accounts · values in ")
           .append(e(cur)).append(" · concentration limit ").append(e(v.concentrationLimitPct())).append("% of net worth</span></section>");
        out.append("<section class=\"kpis cockpit-kpis\">")
           .append(kpi("Net worth", Html.money(p.netWorthInBase()) + " " + cur, "holdings + cash", "accent"))
           .append(kpi("Invested", Html.money(p.investedInBase()) + " " + cur, p.positions().size() + " positions", null))
           .append(kpi("Cash", Html.money(p.cashInBase()) + " " + cur, "all accounts", null))
           .append(top == null ? "" : kpi("Largest issuer", top.issuer() + " " + Html.pct(top.pctOfNetWorth()),
                   top.overLimit() ? "above the limit" : "within the limit", top.overLimit() ? "warning" : null))
           .append(v.ytd().map(y -> kpi("Return YTD", Html.pct(y.netPerformancePct()), y.method(), null)).orElse(""))
           .append("</section>");

        StringBuilder t = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Symbol</th><th>Name</th>"
                + "<th class=\"num\">Shares</th><th class=\"num\">Price</th>" + (returns.isEmpty() ? "" : "<th class=\"num\">Avg cost</th>")
                + "<th class=\"num\">Value (" + e(cur) + ")</th>" + (returns.isEmpty() ? "" : "<th class=\"num\">Gain (" + e(cur) + ")</th>")
                + "<th class=\"num\">% net worth</th><th>Accounts</th><th>Fundamentals</th><th>Price signals (30 days)</th></tr></thead><tbody>");
        for (Portfolio.Position pos : p.positions()) {
            double pctNw = p.netWorthInBase().signum() == 0 ? Double.NaN
                    : pos.valueInBase().doubleValue() * 100 / p.netWorthInBase().doubleValue();
            StringBuilder accts = new StringBuilder();
            pos.sharesByAccount().forEach((a, n) -> accts.append(e(a)).append(" ").append(Html.shares(n)).append("<br>"));
            t.append("<tr><td>").append(symbolLink(pos.symbol())).append("</td><td>").append(e(pos.name())).append("</td><td class=\"num\">")
             .append(Html.shares(pos.quantity())).append("</td><td class=\"num\">").append(Html.money(pos.marketPrice())).append(" ")
             .append(e(pos.currency())).append("</td>");
            var hr = returns.get(pos.symbol());
            if (!returns.isEmpty()) {
                t.append("<td class=\"num\">").append(hr == null ? "–" : Html.money(hr.avgCost())).append("</td>");
            }
            t.append("<td class=\"num\">").append(Html.money(pos.valueInBase())).append("</td>");
            if (!returns.isEmpty()) {
                t.append("<td class=\"num\">").append(hr == null ? "–" : "<span class=\"" + (hr.unrealized().signum() < 0 ? "cockpit-neg" : "cockpit-pos")
                        + "\">" + Html.money(hr.unrealized()) + "</span><br><span class=\"small\">" + PerformancePage.pct(hr.unrealizedPct()) + "</span>")
                 .append("</td>");
            }
            t.append("<td class=\"num\">")
             .append(Html.pct(pctNw)).append("</td><td class=\"small\">").append(accts).append("</td><td>")
             .append(healthCell(v.healthByIssuer().get(Portfolio.issuerOf(pos.symbol())))).append("</td><td>")
             .append(signalsCell(v.signalsBySymbol().get(pos.symbol()))).append("</td></tr>");
        }
        t.append("</tbody></table></div>");
        out.append("<div class=\"cockpit-grid\">");
        out.append(card("Holdings", "Positions", "Market value in " + cur + " from the holdings source; hover a badge for the rule "
                + "and its numbers. Signals: watch and warning level.", t.toString(), "table-card full"));

        StringBuilder iss = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Issuer</th><th>Listings</th>"
                + "<th class=\"num\">Value (" + e(cur) + ")</th><th class=\"num\">% net worth</th><th>Limit</th></tr></thead><tbody>");
        for (Portfolio.IssuerWeight w : v.issuers()) {
            iss.append("<tr><td>").append(e(w.issuer())).append("</td><td>").append(e(String.join(", ", w.symbols())))
               .append("</td><td class=\"num\">").append(Html.money(w.valueInBase())).append("</td><td class=\"num\">")
               .append(Html.pct(w.pctOfNetWorth())).append("</td><td>")
               .append(w.overLimit() ? badge("above " + v.concentrationLimitPct() + "%", "danger") : badge("ok", "success"))
               .append("</td></tr>");
        }
        iss.append("</tbody></table></div>");
        out.append(card("Concentration", "Issuers, all listings combined", "ENB.TO and ENB count as one company.", iss.toString(),
                "table-card"));

        if (!v.accounts().isEmpty()) {
            StringBuilder ac = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Account</th><th>Currency</th>"
                    + "<th class=\"num\">Cash</th><th class=\"num\">Cash in " + e(cur) + "</th></tr></thead><tbody>");
            for (Account a : v.accounts()) {
                ac.append("<tr><td>").append(e(a.name())).append("</td><td>").append(e(a.currency())).append("</td><td class=\"num\">")
                  .append(Html.money(a.cashBalance())).append("</td><td class=\"num\">").append(Html.money(a.cashInBase())).append("</td></tr>");
            }
            ac.append("</tbody></table></div>");
            out.append(card("Accounts", "Cash by account", null, ac.toString(), "table-card"));
        }
        out.append("</div>");
        return out.toString();
    }

    // --- Watch list --------------------------------------------------------------------------------------

    static String watchlist(List<CockpitService.WatchRow> rows, String message) {
        String form = """
                <form class="cockpit-form" hx-post="/watchlist" hx-target="#watchlist-table" hx-swap="outerHTML">
                    <label>Symbol <input name="symbol" required maxlength="20" placeholder="ENB.TO, IBM" autocomplete="off"></label>
                    <label>Note <input name="note" maxlength="200" placeholder="why you watch it"></label>
                    <button type="submit" class="primary-button">Add to watch list</button>
                </form>""";
        return "<div class=\"cockpit-grid\">" + card("Watch list", "Stocks you follow", "Owned or not. Symbols in Yahoo form: TSX "
                + "listings end in .TO. Kept in the Cockpit's own database, independent of the holdings source.",
                form + watchlistTable(rows, message), "table-card full") + "</div>";
    }

    static String watchlistTable(List<CockpitService.WatchRow> rows, String message) {
        StringBuilder t = new StringBuilder("<div id=\"watchlist-table\">");
        if (message != null) {
            t.append(Layout.notice(message));
        }
        if (rows.isEmpty()) {
            t.append("<p class=\"muted\">Nothing on the watch list yet.</p></div>");
            return t.toString();
        }
        t.append("<div class=\"table-scroll\"><table><thead><tr><th>Symbol</th><th>Note</th><th class=\"num\">Last close</th>"
                + "<th class=\"num\">Day</th><th class=\"num\">52-week range</th><th>Nearest alert</th><th>Fundamentals</th>"
                + "<th>Price signals (30 days)</th><th></th></tr></thead><tbody>");
        for (CockpitService.WatchRow r : rows) {
            t.append("<tr><td>").append(symbolLink(r.item().symbol())).append(r.held() ? " " + badge("held", "success") : "")
             .append("</td><td><form class=\"cockpit-note-form\" hx-post=\"/watchlist\" hx-trigger=\"change\" hx-target=\"#watchlist-table\" "
                     + "hx-swap=\"outerHTML\"><input type=\"hidden\" name=\"symbol\" value=\"").append(e(r.item().symbol()))
             .append("\"><input name=\"note\" maxlength=\"200\" aria-label=\"Note for ").append(e(r.item().symbol())).append("\" value=\"")
             .append(e(r.item().note())).append("\" placeholder=\"add a note\"></form></td>");
            if (r.error() != null) {
                t.append("<td colspan=\"6\" class=\"muted\">").append(e(r.error())).append("</td>");
            } else {
                Bar last = r.last();
                t.append("<td class=\"num\">").append(Html.price(last.close())).append("<br><span class=\"muted small\">")
                 .append(e(last.date())).append("</span></td><td class=\"num ").append(r.dayChangePct() != null && r.dayChangePct() < 0 ? "neg" : "pos")
                 .append("\">").append(Html.signedPct(r.dayChangePct())).append("</td><td class=\"num\">").append(Html.money(r.low52w()))
                 .append(" – ").append(Html.money(r.high52w())).append("</td><td>")
                 .append(r.nearestAlert() == null ? "<span class=\"muted\">none</span>"
                         : e(r.nearestAlert().describe()) + "<br><span class=\"muted small\">" + Html.signedPct(r.distanceToAlertPct())
                         + " away</span>")
                 .append("</td><td>").append(healthCell(r.health())).append("</td><td>").append(signalsCell(r.signals())).append("</td>");
            }
            t.append("<td><button class=\"text-button\" hx-post=\"/watchlist/").append(e(Html.sym(r.item().symbol())))
             .append("/remove\" hx-target=\"#watchlist-table\" hx-swap=\"outerHTML\" hx-confirm=\"Remove ")
             .append(e(r.item().symbol())).append(" from the watch list?\">Remove</button></td></tr>");
        }
        t.append("</tbody></table></div></div>");
        return t.toString();
    }

    // --- Stock detail ------------------------------------------------------------------------------------

    /** Options for the search box's drop-down: the symbol is the value, name and exchange the label. */
    static String symbolSuggestions(List<ca.twintigers.research.ports.SymbolSearch.Match> found) {
        StringBuilder b = new StringBuilder();
        for (var m : found) {
            b.append("<option value=\"").append(e(m.symbol())).append("\">").append(e(m.name() + " · " + m.exchange()
                    + (m.type().equals("ETF") ? " · ETF" : ""))).append("</option>");
        }
        return b.toString();
    }

    /** The quote-page chart: ranges, dates, chart type, overlays, zoom, readout, chart, overview strip, explanation. */
    static String chartControls(String symbol) {
        StringBuilder b = new StringBuilder("<div class=\"qc\" id=\"qc\" data-symbol=\"").append(e(Html.sym(symbol))).append("\">");
        b.append("<div class=\"qc-row\"><div class=\"qc-seg\" role=\"group\" aria-label=\"Range\">");
        for (String r : StockChart.RANGES) {
            b.append("<button type=\"button\" data-range=\"").append(r).append("\" aria-pressed=\"").append(r.equals("1Y")).append("\">")
             .append(r.equals("MAX") ? "Max" : r).append("</button>");
        }
        b.append("</div><form class=\"qc-dates\" id=\"qc-dates\"><label>From <input type=\"date\" name=\"from\" required></label>"
                + "<label>To <input type=\"date\" name=\"to\"></label><button type=\"submit\" class=\"secondary-button\">Apply</button></form>"
                + "</div>");
        b.append("<div class=\"qc-row\"><div class=\"qc-seg\" role=\"group\" aria-label=\"Chart type\">"
                + "<button type=\"button\" data-type=\"candle\" aria-pressed=\"true\">Candle</button>"
                + "<button type=\"button\" data-type=\"line\" aria-pressed=\"false\">Line</button>"
                + "<button type=\"button\" data-type=\"area\" aria-pressed=\"false\">Area</button></div>"
                + "<label class=\"qc-check qc-sma50\"><input type=\"checkbox\" id=\"qc-sma50\" checked> 50-day avg</label>"
                + "<label class=\"qc-check qc-sma200\"><input type=\"checkbox\" id=\"qc-sma200\" checked> 200-day avg</label>"
                + "<label class=\"qc-check\"><input type=\"checkbox\" id=\"qc-volume\" checked> Volume</label>"
                + "<label class=\"qc-check\"><input type=\"checkbox\" id=\"qc-log\"> Log scale</label>"
                + "<label id=\"qc-bars-wrap\" hidden>Bars <select id=\"qc-bars\"><option value=\"1m\">1 min</option>"
                + "<option value=\"5m\">5 min</option><option value=\"15m\">15 min</option><option value=\"30m\">30 min</option>"
                + "<option value=\"1h\">60 min</option></select></label>"
                + "<label id=\"qc-markers-wrap\">Markers <select id=\"marker-filter\"><option value=\"warning\">Warnings only</option>"
                + "<option value=\"watch\" selected>Watch and warnings</option><option value=\"info\">All signals</option></select></label>"
                + "<div class=\"qc-seg qc-zoom\" role=\"group\" aria-label=\"Zoom and pan\">"
                + "<button type=\"button\" data-zoom=\"left\" aria-label=\"Pan left\" title=\"Pan left\">◀</button>"
                + "<button type=\"button\" data-zoom=\"in\" aria-label=\"Zoom in\" title=\"Zoom in\">+</button>"
                + "<button type=\"button\" data-zoom=\"out\" aria-label=\"Zoom out\" title=\"Zoom out\">−</button>"
                + "<button type=\"button\" data-zoom=\"right\" aria-label=\"Pan right\" title=\"Pan right\">▶</button>"
                + "<button type=\"button\" data-zoom=\"reset\">Reset</button></div></div>");
        b.append("<div class=\"cockpit-chart-wrap qc-main\"><div id=\"qc-readout\" class=\"qc-readout\"></div>"
                + "<div id=\"chart\" class=\"cockpit-chart\" data-symbol=\"").append(e(Html.sym(symbol)))
         .append("\" role=\"img\" aria-label=\"Price chart of ").append(e(symbol)).append("\"></div>"
                + "<div id=\"qc-select\" class=\"qc-select\" hidden></div></div>"
                + "<div class=\"qc-overview-wrap\"><div id=\"qc-overview\" class=\"qc-overview\"></div>"
                + "<div id=\"qc-window\" class=\"qc-window\" hidden><span class=\"qc-handle left\"></span><span class=\"qc-handle right\">"
                + "</span></div></div>"
                + "<p class=\"qc-status muted small\"><span id=\"chart-asof\"></span> <span id=\"marker-count\"></span></p>"
                + "<section id=\"signal-explain\" class=\"cockpit-explain\" hidden aria-live=\"polite\"></section></div>");
        return b.toString();
    }

    static String symbolOptions(String current, List<String> held, List<String> watched) {
        java.util.TreeSet<String> h = new java.util.TreeSet<>(held);
        java.util.TreeSet<String> w = new java.util.TreeSet<>(watched);
        w.removeAll(h);
        StringBuilder b = new StringBuilder();
        if (!h.contains(current) && !w.contains(current)) {
            b.append("<option selected>").append(e(current)).append("</option>");
        }
        group(b, "Holdings", h, current);
        group(b, "Watch list", w, current);
        return b.toString();
    }

    private static void group(StringBuilder b, String label, java.util.Set<String> symbols, String current) {
        if (symbols.isEmpty()) {
            return;
        }
        b.append("<optgroup label=\"").append(e(label)).append("\">");
        for (String s : symbols) {
            b.append("<option").append(s.equals(current) ? " selected" : "").append(">").append(e(s)).append("</option>");
        }
        b.append("</optgroup>");
    }

    static String stock(CockpitService.StockView v) {
        return stock(v, List.of(), List.of());
    }

    /** Symbol picker: a drop-down of your holdings and watch list (opens on selection), plus a box for any other symbol. */
    static String stock(CockpitService.StockView v, List<String> held, List<String> watched) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"context-bar page-context-bar\"><form class=\"cockpit-form inline\" id=\"symbol-picker\" "
                + "action=\"/stock\" method=\"get\"><label>Symbol <select name=\"symbol\" id=\"symbol-select\">")
           .append(symbolOptions(v.symbol(), held, watched)).append("</select></label>"
                + "<label>Search all stocks <input name=\"other\" id=\"symbol-other\" maxlength=\"40\" "
                + "placeholder=\"Name or symbol, e.g. Royal Bank\" autocomplete=\"off\" list=\"symbol-suggestions\" "
                + "hx-get=\"/symbols/suggest\" hx-trigger=\"input changed delay:300ms\" hx-target=\"#symbol-suggestions\" "
                + "hx-swap=\"innerHTML\"></label><datalist id=\"symbol-suggestions\"></datalist>"
                + "<button type=\"submit\" class=\"secondary-button\">Open</button></form>")
           .append(v.held() ? badge("held", "success") : "").append(v.watched() ? " " + badge("on watch list", "watch") : "")
           .append("</section>");
        if (v.error() != null) {
            out.append(Layout.notice(v.error()));
            return out.toString();
        }
        List<Bar> bars = v.bars();
        Bar last = bars.getLast();
        Double change = bars.size() > 1 ? (last.close() / bars.get(bars.size() - 2).close() - 1) * 100 : null;
        double atr = v.atr().length == 0 ? Double.NaN : v.atr()[v.atr().length - 1];
        double hi = bars.stream().skip(Math.max(0, bars.size() - 252)).mapToDouble(Bar::high).max().orElse(Double.NaN);
        double lo = bars.stream().skip(Math.max(0, bars.size() - 252)).mapToDouble(Bar::low).min().orElse(Double.NaN);
        out.append("<section class=\"kpis cockpit-kpis\">")
           .append(kpi("Last close", Html.price(last.close()), "on " + last.date(), "accent"))
           .append(kpi("Day", Html.signedPct(change), "vs previous close", change != null && change < 0 ? "negative" : "positive"))
           .append(kpi("52-week range", Html.price(lo) + " – " + Html.price(hi), "daily highs and lows", null))
           .append(kpi("ATR (14)", Html.price(atr), "average daily range", null))
           .append("</section>");
        out.append("<div class=\"cockpit-grid\">");
        out.append(card("Chart", v.symbol(), "Drag across the chart to zoom in; the mouse wheel zooms, Shift + wheel pans; + / − and "
                + "Reset do the same. The strip below the chart is the whole period: drag its window to move. Markers are the price "
                + "signals (daily views); dashed lines are your alerts. Daily bars: " + v.marketSource() + ". 1D and 5D: delayed "
                + "intraday bars, stored locally.", chartControls(v.symbol()), "chart-card full"));

        StringBuilder sig = new StringBuilder();
        Signals s = v.signals();
        if (s == null || s.unavailable() != null) {
            sig.append(Layout.notice(s == null ? "No signals." : s.unavailable()));
        } else if (s.signals().isEmpty()) {
            sig.append("<p class=\"muted\">No signal in this period.</p>");
        } else {
            sig.append("<p class=\"muted small\">Click a row to see it on the chart: the day is zoomed in, the bars the rule "
                    + "looked at are highlighted, and its levels and numbers are explained.</p>");
            sig.append("<div class=\"table-scroll\"><table class=\"cockpit-signal-table\"><thead><tr><th>Date</th><th>Severity</th>"
                    + "<th>Rule</th><th>What happened</th></tr></thead><tbody>");
            for (Signal g : s.signals().reversed()) {
                sig.append("<tr class=\"cockpit-signal-row\" tabindex=\"0\" data-time=\"").append(e(g.date())).append("\" data-rule=\"")
                   .append(e(g.ruleId())).append("\" title=\"Show on the chart\"><td>").append(e(g.date())).append("</td><td>").append(severityBadge(g.severity())).append("</td><td>")
                   .append(e(g.ruleName())).append("<br><span class=\"muted small\">").append(e(g.ruleId())).append("</span></td><td>")
                   .append(e(g.text())).append("</td></tr>");
            }
            sig.append("</tbody></table></div>");
        }
        out.append(card("Price signals", "All signals in the period", "Mechanical rules on daily bars; they describe what price "
                + "and volume did, not what they will do. Severity reflects the replay tuning (docs/alerts/rule-tuning.md).",
                sig.toString(), "table-card full"));

        out.append(card("Your alerts", "Alerts on " + v.symbol(), "Add a level here; it is checked every 30 minutes in market hours.",
                alertsFor(v.symbol(), v.alerts()), "table-card"));
        out.append(card("Fundamentals", "Financial health", null, healthTable(v.health()), "table-card full"));
        out.append("</div>");
        return out.toString();
    }

    static String alertsFor(String symbol, List<Alert> alerts) {
        StringBuilder b = new StringBuilder("<div id=\"stock-alerts\">");
        if (alerts.isEmpty()) {
            b.append("<p class=\"muted\">No alerts on this symbol.</p>");
        } else {
            b.append("<ul class=\"cockpit-list\">");
            alerts.forEach(a -> b.append("<li>#").append(a.id()).append(" ").append(e(a.describe())).append(" — ")
                    .append(e(a.state().status().name().toLowerCase(Locale.ROOT))).append("</li>"));
            b.append("</ul>");
        }
        b.append("""
                <form class="cockpit-form" hx-post="/alerts/threshold" hx-target="#alerts-message" hx-swap="innerHTML">
                    <input type="hidden" name="symbol" value="%s">
                    <label>Kind <select name="kind"><option value="above">above</option><option value="below">below</option></select></label>
                    <label>Level <input name="level" type="number" step="0.01" min="0" required></label>
                    <label>Note <input name="note" maxlength="200"></label>
                    <button type="submit" class="primary-button">Add alert</button>
                </form><div id="alerts-message"></div></div>""".formatted(e(symbol)));
        return b.toString();
    }

    static String healthTable(Health h) {
        if (h == null || h.unavailable() != null) {
            return Layout.notice(h == null ? "No fundamentals." : h.unavailable());
        }
        HealthTable t = h.table();
        StringBuilder b = new StringBuilder("<p>").append(e(t.company())).append(" (SEC CIK ").append(t.cik()).append(", reporting currency ")
                .append(e(t.currency())).append(", millions unless noted). Debt tags: ").append(e(t.debtScheme())).append(".</p>");
        b.append("<div class=\"table-scroll\"><table><thead><tr><th></th>");
        t.columns().forEach(c -> b.append("<th class=\"num\">").append(e(c.kind())).append(" ").append(e(c.period().end())).append("</th>"));
        b.append("</tr></thead><tbody>");
        t.rows().forEach((name, cells) -> {
            b.append("<tr><th scope=\"row\">").append(e(name)).append("</th>");
            for (Cell c : cells) {
                String val = c == null || c.value() == null ? "–" : fmt(name, c.value().doubleValue());
                String title = c instanceof Fact f ? f.concept() + ", " + f.form() + " filed " + f.filed() + ", accession " + f.accession()
                        : c instanceof ca.twintigers.research.domain.provenance.Calc k ? k.formula() : "";
                b.append("<td class=\"num\" title=\"").append(e(title)).append("\">").append(e(val)).append("</td>");
            }
            b.append("</tr>");
        });
        b.append("</tbody></table></div><h3 class=\"cockpit-subhead\">Flags</h3>");
        if (t.flags().isEmpty()) {
            b.append("<p class=\"muted\">None triggered.</p>");
        } else {
            b.append("<ul class=\"cockpit-list\">");
            t.flags().forEach(f -> b.append("<li>").append(severityBadge(f.severity())).append(" ").append(e(f.text()))
                    .append(" <span class=\"muted small\">").append(e(f.ruleId())).append("</span></li>"));
            b.append("</ul>");
        }
        b.append("<details class=\"cockpit-details\"><summary>Sources, formulas and assumptions</summary><pre>")
         .append(e(HealthMarkdown.render(t))).append("</pre></details>");
        return b.toString();
    }

    static String fmt(String row, double v) {
        if (row.contains("%")) {
            return String.format(Locale.ROOT, "%.1f%%", v);
        }
        if (row.contains("conversion")) {
            return String.format(Locale.ROOT, "%.2f×", v);
        }
        if (row.toLowerCase(Locale.ROOT).contains("shares")) {
            return String.format(Locale.ROOT, "%,.1fM", v / 1e6);
        }
        return String.format(Locale.ROOT, "%,.1f", v / 1e6);
    }

    // --- Alerts ------------------------------------------------------------------------------------------

    static String alerts(List<Alert> alerts, List<Subscription> subs, List<String> channels, List<RunLog.Run> runs,
                         List<Outbox.Entry> notifications, List<PriceRule> rules) {
        StringBuilder out = new StringBuilder("<div class=\"cockpit-grid\">");
        out.append(card("Thresholds", "Your price alerts", "Checked every 30 minutes in market hours and at the close.",
                alertsTable(alerts, null) + """
                <form class="cockpit-form" hx-post="/alerts/threshold" hx-target="#alerts-table" hx-swap="outerHTML">
                    <label>Symbol <input name="symbol" required maxlength="20" placeholder="ENB.TO"></label>
                    <label>Kind <select name="kind"><option value="above">above</option><option value="below">below</option>
                        <option value="move_pct">% move from reference</option><option value="trailing_atr">ATR trailing stop</option></select></label>
                    <label>Level <input name="level" type="number" step="0.01" min="0"></label>
                    <label>% move <input name="pct" type="number" step="0.1"></label>
                    <label>Reference <input name="reference" type="number" step="0.01" min="0"></label>
                    <label>ATR × <input name="atr" type="number" step="0.1" min="0"></label>
                    <label>Note <input name="note" maxlength="200"></label>
                    <button type="submit" class="primary-button">Add alert</button>
                </form>""", "table-card full"));
        StringBuilder ruleOptions = new StringBuilder("<option value=\"*\">all price rules</option>");
        rules.forEach(r -> ruleOptions.append("<option value=\"").append(e(r.id())).append("\">").append(e(r.id())).append("</option>"));
        out.append(card("Subscriptions", "Rules that notify you", "Defaults come from the two-year replay (docs/alerts/replay-defaults.md).",
                subscriptionsTable(subs) + """
                <form class="cockpit-form" hx-post="/alerts/subscription" hx-target="#subscriptions-table" hx-swap="outerHTML">
                    <label>Scope <input name="scope" value="*" maxlength="20" title="* for all holdings, or a symbol / issuer"></label>
                    <label>Source <select name="source"><option value="price">price rules</option><option value="fundamental">fundamental flags</option></select></label>
                    <label>Rule <select name="rule">%s</select></label>
                    <label>Min severity <select name="minSeverity"><option>warning</option><option>watch</option><option>info</option></select></label>
                    <button type="submit" class="primary-button">Subscribe</button>
                </form>""".formatted(ruleOptions), "table-card full"));

        StringBuilder st = new StringBuilder("<p>Channels: ").append(e(channels.isEmpty() ? "none configured" : String.join(", ", channels)))
                .append(". Notification delivery is on hold: pop-up delivery is unconfirmed (see the alert guide).</p>");
        st.append("<div class=\"table-scroll\"><table><thead><tr><th>Run</th><th>Mode</th><th>Started (ET)</th><th>Result</th>"
                + "<th class=\"num\">Symbols</th><th class=\"num\">Queued</th></tr></thead><tbody>");
        for (RunLog.Run r : runs) {
            st.append("<tr><td>#").append(r.id()).append("</td><td>").append(e(r.mode())).append("</td><td>").append(e(et(r.startedAt())))
              .append("</td><td>").append(r.ok() ? badge("ok", "success") : badge("failed", "danger")).append("</td><td class=\"num\">")
              .append(r.symbols()).append("</td><td class=\"num\">").append(r.notifications()).append("</td></tr>");
        }
        st.append("</tbody></table></div>");
        out.append(card("Monitor", "Scheduled runs", "The Windows task \"Twintigers Research Monitor\".", st.toString(), "table-card"));

        StringBuilder nt = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Created (ET)</th><th>Channel</th>"
                + "<th>Title</th><th>Status</th></tr></thead><tbody>");
        for (Outbox.Entry n : notifications) {
            nt.append("<tr><td>").append(e(et(n.createdAt()))).append("</td><td>").append(e(n.channel())).append("</td><td title=\"")
              .append(e(n.body())).append("\">").append(e(n.title())).append("</td><td>")
              .append(n.sentAt() != null ? badge("sent", "success") : badge("pending (" + n.attempts() + ")", "watch")).append("</td></tr>");
        }
        nt.append("</tbody></table></div>");
        out.append(card("History", "Notifications", "Hover a title for the full message.", nt.toString(), "table-card"));
        out.append("</div>");
        return out.toString();
    }

    static String alertsTable(List<Alert> alerts, String message) {
        StringBuilder t = new StringBuilder("<div id=\"alerts-table\">");
        if (message != null) {
            t.append(Layout.notice(message));
        }
        if (alerts.isEmpty()) {
            return t.append("<p class=\"muted\">No price alerts yet.</p></div>").toString();
        }
        Instant now = Instant.now();
        t.append("<div class=\"table-scroll\"><table><thead><tr><th>#</th><th>Alert</th><th>State</th><th>Note</th><th></th></tr></thead><tbody>");
        for (Alert a : alerts) {
            t.append("<tr><td>").append(a.id()).append("</td><td>").append(symbolLink(a.symbol())).append(" ")
             .append(e(a.describe().substring(a.symbol().length()))).append("</td><td>")
             .append(a.state().status() == ca.twintigers.research.domain.alerts.AlertState.Status.TRIGGERED ? badge("triggered", "danger")
                     : badge("armed", "success"))
             .append(a.muted(now) ? " " + badge("muted until " + et(a.mutedUntil()), "watch") : "").append("</td><td>").append(e(a.note()))
             .append("</td><td>").append(actions("alert", a.id(), "#alerts-table")).append("</td></tr>");
        }
        return t.append("</tbody></table></div></div>").toString();
    }

    static String subscriptionsTable(List<Subscription> subs) {
        StringBuilder t = new StringBuilder("<div id=\"subscriptions-table\"><div class=\"table-scroll\"><table><thead><tr><th>#</th>"
                + "<th>Source</th><th>Rule</th><th>Min severity</th><th>Scope</th><th>Origin</th><th></th></tr></thead><tbody>");
        Instant now = Instant.now();
        for (Subscription s : subs) {
            t.append("<tr><td>").append(s.id()).append("</td><td>").append(e(s.source().name().toLowerCase(Locale.ROOT))).append("</td><td>")
             .append(e(s.rule())).append("</td><td>").append(severityBadge(s.minSeverity())).append("</td><td>")
             .append(e(s.scope().equals("*") ? "all holdings" : s.scope())).append("</td><td>").append(e(s.origin()))
             .append(s.mutedUntil() != null && now.isBefore(s.mutedUntil()) ? " " + badge("muted", "watch") : "")
             .append("</td><td>").append(actions("subscription", s.id(), "#subscriptions-table")).append("</td></tr>");
        }
        return t.append("</tbody></table></div></div>").toString();
    }

    static String actions(String target, long id, String swapTarget) {
        return "<button class=\"text-button\" hx-post=\"/alerts/" + target + "/" + id + "/mute\" hx-target=\"" + swapTarget
                + "\" hx-swap=\"outerHTML\">Mute 7 days</button> <button class=\"text-button danger\" hx-post=\"/alerts/" + target + "/" + id
                + "/remove\" hx-target=\"" + swapTarget + "\" hx-swap=\"outerHTML\" hx-confirm=\"Remove " + target + " #" + id
                + "?\">Remove</button>";
    }

    static String et(Instant i) {
        return i == null ? "–" : i.atZone(ET).toLocalDateTime().withNano(0).toString().replace('T', ' ');
    }

    // --- Rule catalog ------------------------------------------------------------------------------------

    static String rules(List<PriceRule> rules) {
        StringBuilder t = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Id</th><th>Name</th><th>Severity</th>"
                + "<th>Direction</th><th>Definition</th></tr></thead><tbody>");
        for (PriceRule r : rules) {
            t.append("<tr><td>").append(e(r.id())).append("</td><td>").append(e(r.name())).append("</td><td>").append(severityBadge(r.severity()))
             .append("</td><td>").append(e(r.direction().name().toLowerCase(Locale.ROOT))).append("</td><td>").append(e(r.definition()))
             .append("</td></tr>");
        }
        t.append("</tbody></table></div>");
        return "<div class=\"cockpit-grid\">" + card("Definitions", "Price, candlestick and momentum rules", "Thresholds are in "
                + "java/config/rules.properties. Fundamental rules F01–F11 are in docs/architecture/rule-catalog.md.", t.toString(),
                "table-card full") + "</div>";
    }
}
