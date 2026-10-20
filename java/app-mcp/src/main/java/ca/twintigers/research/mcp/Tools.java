package ca.twintigers.research.mcp;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.application.PriceSignalService;
import ca.twintigers.research.application.Reports;
import ca.twintigers.research.domain.market.RuleEngine;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.domain.rules.Severity;
import ca.twintigers.research.ports.DataSourceException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The MCP tools. Each returns JSON text: {report_markdown, data} on success, {error} when a source fails. */
final class Tools {

    private static final Logger LOG = LoggerFactory.getLogger(Tools.class);

    private final Wiring w;
    private final ObjectMapper json = new ObjectMapper();

    Tools(Wiring wiring) {
        this.w = wiring;
    }

    @FunctionalInterface
    interface Handler {
        Map<String, Object> handle(Map<String, Object> args) throws Exception;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    /** Ordered name → schema map (Map.of stops at 10 entries). */
    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private SyncToolSpecification tool(String name, String title, String description, Map<String, Object> schema, Handler h) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).title(title).description(description).inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(title, true, false, true, true, null)).build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, req) -> {
            Map<String, Object> out;
            try {
                out = h.handle(req.arguments() == null ? Map.of() : req.arguments());
            } catch (DataSourceException | IllegalArgumentException e) {
                out = Map.of("error", String.valueOf(e.getMessage()));
            } catch (Exception e) {
                LOG.warn("Tool {} failed: {}", name, e.toString());
                out = Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json.writeValueAsString(out)))).build();
        }).build();
    }

    /** Like {@link #tool} but annotated as writing (to the local alert store only). */
    private SyncToolSpecification writeTool(String name, String title, String description, Map<String, Object> schema, Handler h) {
        SyncToolSpecification base = tool(name, title, description, schema, h);
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).title(title).description(description).inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(title, false, false, false, false, null)).build();
        return SyncToolSpecification.builder().tool(tool).callHandler(base.callHandler()).build();
    }

    private String alertList() {
        StringBuilder sb = new StringBuilder("### Threshold alerts\n\n");
        var list = w.alerts.alerts();
        if (list.isEmpty()) {
            sb.append("None.\n");
        }
        java.time.Instant now = java.time.Instant.now();
        list.forEach(x -> sb.append("- #").append(x.id()).append(' ').append(x.describe()).append(" — ").append(x.state().status())
                .append(x.muted(now) ? " (muted until " + x.mutedUntil() + ")" : "").append(x.note() == null ? "" : " — " + x.note()).append('\n'));
        sb.append("\n### Rule subscriptions\n\n");
        w.alerts.subscriptions().forEach(s -> sb.append("- #").append(s.id()).append(' ').append(s.describe())
                .append(s.mutedUntil() != null && now.isBefore(s.mutedUntil()) ? " (muted until " + s.mutedUntil() + ")" : "").append('\n'));
        sb.append("\nChannels: ").append(w.dispatcher.channels());
        return sb.toString();
    }

    static Double opt(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v == null ? null : v instanceof Number n ? n.doubleValue() : Double.valueOf(v.toString());
    }

    static String str(Map<String, Object> a, String k, String def) {
        Object v = a.get(k);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    static int integer(Map<String, Object> a, String k, int def) {
        Object v = a.get(k);
        return v instanceof Number n ? n.intValue() : v == null ? def : Integer.parseInt(v.toString());
    }

    static double number(Map<String, Object> a, String k, double def) {
        Object v = a.get(k);
        return v instanceof Number n ? n.doubleValue() : v == null ? def : Double.parseDouble(v.toString());
    }

    @SuppressWarnings("unchecked")
    static List<String> list(Map<String, Object> a, String k) {
        Object v = a.get(k);
        if (v instanceof List<?> l) {
            return ((List<Object>) l).stream().map(Object::toString).toList();
        }
        return v == null || v.toString().isBlank() ? List.of() : List.of(v.toString().split("[,\\s]+"));
    }

    private List<String> holdings() throws DataSourceException {
        return w.portfolio.portfolio().positions().stream().map(Portfolio.Position::symbol).toList();
    }

    List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> t = new ArrayList<>();

        t.add(tool("financial_health", "Financial health audit (Java)",
                "Financial health audit from SEC XBRL filings: the last `years` fiscal years plus year-to-date vs prior year. "
                        + "Revenue, growth, margins, net income, operating and free cash flow, capex, cash, total debt (one consistent "
                        + "tag scheme per company, stated), net debt, receivables, inventory, diluted shares and dilution; every "
                        + "reported figure cites its filing and XBRL tag, every calculated one its formula. Flags F01-F11 (e.g. "
                        + "negative or falling free cash flow, rising net debt, dilution); banks are exempt from the cash-conversion "
                        + "test. Use the US/SEC ticker (TELUS = TU). Present report_markdown as is, then explain.",
                schema(Map.of("ticker_or_cik", prop("string", "SEC ticker (e.g. ENB, TU, FTS, BNS, IBM) or CIK number"),
                        "years", prop("integer", "Fiscal years, 1-5 (default 3)")), List.of("ticker_or_cik")),
                a -> {
                    var r = w.health.health(str(a, "ticker_or_cik", ""), integer(a, "years", 3));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("company", r.company());
                    out.put("report_markdown", r.markdown());
                    out.put("flags", r.table().flags());
                    out.put("debt_scheme", r.table().debtScheme());
                    out.put("data", r.table());
                    return out;
                }));

        t.add(tool("price_signals", "Price-action and K-line signals",
                "Runs the price-action rules (buyer-controlled jump, seller-controlled drop, gaps, sustained decline on rising "
                        + "volume, support/resistance breaks), candlestick (K-line) patterns (bearish engulfing, shooting star, "
                        + "evening star, hanging man, dark cloud cover, three black crows, and bullish counterparts) and RSI/MACD "
                        + "crosses on daily bars for the last `lookback_days`. Each signal has its date, severity and the exact bar "
                        + "and indicator values that triggered it (evidence). Omit `symbols` to use all Ghostfolio holdings. Symbols "
                        + "use Yahoo form: TSX ends in .TO (ENB.TO). min_severity: info, watch (default) or warning.",
                schema(Map.of("symbols", prop("string", "Comma-separated symbols, e.g. \"ENB.TO, IBM\"; empty = all holdings"),
                        "lookback_days", prop("integer", "Calendar days to report (default 30)"),
                        "min_severity", prop("string", "info | watch | warning (default watch)")), List.of()),
                a -> {
                    List<String> symbols = list(a, "symbols");
                    if (symbols.isEmpty()) {
                        symbols = holdings();
                    }
                    Severity min = Severity.valueOf(str(a, "min_severity", "watch").toUpperCase(Locale.ROOT));
                    List<Object> data = new ArrayList<>();
                    StringBuilder md = new StringBuilder();
                    for (String s : symbols) {
                        try {
                            var r = w.prices.signals(s, integer(a, "lookback_days", 30), min);
                            data.add(r);
                            md.append(Reports.signals(r)).append("\n\n");
                        } catch (DataSourceException e) {
                            data.add(Map.of("symbol", s, "error", e.getMessage()));
                            md.append("## ").append(s).append("\n\nn/a: ").append(e.getMessage()).append("\n\n");
                        }
                    }
                    return Map.of("min_severity", min.label(), "report_markdown", md.toString().strip(), "data", data);
                }));

        t.add(tool("replay_rules", "Replay rules over history",
                "Runs every price rule over each day of the last `years` years (no look-ahead) for the given symbols (default: "
                        + "all holdings) and reports, per rule, how often it fired and the average price move 5 and 20 trading "
                        + "days later, and how often the move went the rule's way. Use it to judge which rules are informative "
                        + "for these stocks and to tune thresholds. Historical behaviour does not predict future results.",
                schema(Map.of("symbols", prop("string", "Comma-separated symbols; empty = all holdings"),
                        "years", prop("integer", "Years of history (default 2)")), List.of()),
                a -> {
                    List<String> symbols = list(a, "symbols");
                    if (symbols.isEmpty()) {
                        symbols = holdings();
                    }
                    List<RuleEngine.Replay> replays = new ArrayList<>();
                    Map<String, Object> per = new LinkedHashMap<>();
                    List<String> ok = new ArrayList<>();
                    for (String s : symbols) {
                        try {
                            PriceSignalService.ReplayReport r = w.prices.replay(s, integer(a, "years", 2));
                            replays.add(r.replay());
                            ok.add(s);
                            per.put(s, Map.of("from", String.valueOf(r.replay().from()), "to", String.valueOf(r.replay().to()),
                                    "trading_days", r.replay().days(), "stats", r.replay().stats()));
                        } catch (DataSourceException e) {
                            per.put(s, Map.of("error", e.getMessage()));
                        }
                    }
                    var combined = Reports.combine(replays);
                    return Map.of("report_markdown", Reports.combinedReplay(ok, combined), "combined", combined, "per_symbol", per);
                }));

        t.add(tool("portfolio_review", "Portfolio review (Java)",
                "Review of the Ghostfolio portfolio: value, cash and net worth; each issuer's weight across listings (ENB.TO + "
                        + "ENB) with a concentration flag above max_position_pct % of net worth; each issuer's fundamental flags "
                        + "(SEC; Kinaxis has no SEC data here); and each holding's price signals at watch/warning level over the "
                        + "last signal_days days. Present report_markdown as is, then explain. Not investment advice.",
                schema(Map.of("max_position_pct", prop("number", "Concentration limit, % of net worth (default 20)"),
                        "signal_days", prop("integer", "Days of price signals (default 30)")), List.of()),
                a -> {
                    var r = w.review.review(number(a, "max_position_pct", 20), integer(a, "signal_days", 30));
                    var filtered = new ca.twintigers.research.application.PortfolioReviewService.Review(r.baseCurrency(),
                            r.investedInBase(), r.cashInBase(), r.netWorthInBase(), r.concentrationLimitPct(), r.positions(),
                            r.issuers(), r.priceSignals().stream().map(s -> new ca.twintigers.research.application
                                    .PortfolioReviewService.SymbolSignals(s.symbol(), s.asOf(), s.signals().stream()
                                    .filter(g -> g.severity() != Severity.INFO).toList(), s.error())).toList(),
                            r.signalLookbackDays());
                    return Map.of("report_markdown", Reports.review(filtered), "data", filtered);
                }));

        // --- alerts: the only write tools; they write the user's local alert store, never Ghostfolio -------------
        t.add(writeTool("add_alert", "Add a price alert or rule subscription",
                "Adds an alert to the user's local alert store (never touches Ghostfolio or any account). type: "
                        + "above | below (needs level), move_pct (needs pct, e.g. -8, and reference price), trailing_atr (needs "
                        + "atr_multiple, optional since date): threshold alerts on one symbol (Yahoo form, ENB.TO). type: "
                        + "subscription: notify when a rule fires; needs source (price | fundamental), rule (id like "
                        + "K05_dark_cloud_cover, a prefix like PA*, or *), optional scope (symbol, issuer or * for all holdings, "
                        + "default *) and min_severity (info | watch | warning, default warning). Only add what the user asked for; "
                        + "confirm the exact alert back to the user.",
                schema(props("type", prop("string", "above | below | move_pct | trailing_atr | subscription"),
                        "symbol", prop("string", "Symbol for threshold alerts, e.g. ENB.TO, IBM"),
                        "level", prop("number", "Price level for above/below"),
                        "pct", prop("number", "Percent move for move_pct: positive = up, negative = down"),
                        "reference", prop("number", "Reference price for move_pct"),
                        "atr_multiple", prop("number", "ATR multiple for trailing_atr, e.g. 3"),
                        "since", prop("string", "Start date (YYYY-MM-DD) for the trailing stop's highest close; default today"),
                        "note", prop("string", "Optional note shown in the notification"),
                        "source", prop("string", "Subscription: price | fundamental"),
                        "rule", prop("string", "Subscription: rule id, prefix*, or *"),
                        "scope", prop("string", "Subscription: symbol, issuer, or * (default)"),
                        "min_severity", prop("string", "Subscription: info | watch | warning (default warning)")), List.of("type")),
                a -> {
                    String type = str(a, "type", "");
                    if (type.equalsIgnoreCase("subscription")) {
                        var s = w.alerts.subscribe(str(a, "scope", "*"), str(a, "source", ""), str(a, "rule", "*"),
                                str(a, "min_severity", "warning"), "user");
                        return Map.of("added", "subscription #" + s.id() + ": " + s.describe(), "subscription", s);
                    }
                    var al = w.alerts.addThreshold(type, str(a, "symbol", ""), opt(a, "level"), opt(a, "pct"), opt(a, "reference"),
                            opt(a, "atr_multiple"), a.get("since") == null ? null : java.time.LocalDate.parse(a.get("since").toString()),
                            str(a, "note", null));
                    return Map.of("added", "alert #" + al.id() + ": " + al.describe(), "alert", al);
                }));
        t.add(tool("list_alerts", "List alerts and subscriptions",
                "the user's threshold alerts (with state: ARMED or TRIGGERED, and mute) and rule subscriptions, plus the "
                        + "configured notification channels.",
                schema(Map.of(), List.of()),
                a -> Map.of("report_markdown", alertList(), "alerts", w.alerts.alerts(), "subscriptions", w.alerts.subscriptions(),
                        "channels", w.dispatcher.channels())));
        t.add(writeTool("remove_alert", "Remove an alert or subscription",
                "Deactivates a threshold alert or a rule subscription in the local alert store (kept for the record). "
                        + "target: alert | subscription; id from list_alerts.",
                schema(Map.of("target", prop("string", "alert | subscription"), "id", prop("integer", "Id from list_alerts")),
                        List.of("target", "id")),
                a -> {
                    boolean ok = w.alerts.remove(str(a, "target", "alert"), integer(a, "id", -1));
                    return ok ? Map.of("removed", str(a, "target", "alert") + " #" + integer(a, "id", -1))
                            : Map.of("error", "No active " + str(a, "target", "alert") + " #" + integer(a, "id", -1) + ".");
                }));
        t.add(writeTool("mute_alert", "Mute an alert or subscription",
                "Silences a threshold alert or a subscription for `days` days (default 7) or `hours`. The alert keeps "
                        + "tracking its state while muted.",
                schema(Map.of("target", prop("string", "alert | subscription"), "id", prop("integer", "Id from list_alerts"),
                        "days", prop("integer", "Days to mute (default 7)"), "hours", prop("integer", "Hours to mute, instead of days")),
                        List.of("target", "id")),
                a -> {
                    java.time.Duration d = a.get("hours") != null ? java.time.Duration.ofHours(integer(a, "hours", 1))
                            : java.time.Duration.ofDays(integer(a, "days", 7));
                    boolean ok = w.alerts.mute(str(a, "target", "alert"), integer(a, "id", -1), d);
                    return ok ? Map.of("muted", str(a, "target", "alert") + " #" + integer(a, "id", -1) + " for " + d.toHours() + " hours")
                            : Map.of("error", "No active " + str(a, "target", "alert") + " #" + integer(a, "id", -1) + ".");
                }));
        t.add(tool("alert_status", "Monitor status",
                "Is the scheduled monitor running? Last runs (mode, time, result), last successful evaluation, notifications "
                        + "queued/sent/failed, channels. Warn the user if the last successful evaluation is more than a trading day old.",
                schema(Map.of(), List.of()),
                a -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("last_successful_evaluation", w.alertDb.lastSuccessfulEvaluation().map(r -> r.finishedAt().toString()).orElse("never"));
                    out.put("channels", w.dispatcher.channels());
                    out.put("pending_notifications", w.alertDb.pendingCount());
                    out.put("recent_runs", w.alertDb.recentRuns(10));
                    out.put("recent_notifications", w.alertDb.recentEntries(10));
                    return out;
                }));

        t.add(tool("rule_catalog", "Rule catalog",
                "Lists every rule (fundamental F01-F11, price action PA01-PA07, candlestick K01-K09, momentum M01-M04) with its "
                        + "severity, direction and exact definition, plus the thresholds in force (defaults overridden by "
                        + "java/config/rules.properties).",
                schema(Map.of(), List.of()),
                a -> Map.of("report_markdown", Reports.catalog(w.prices.engine().rules(), List.of()),
                        "fundamental_thresholds", w.thresholds, "price_thresholds", w.priceConfig)));
        return t;
    }
}
