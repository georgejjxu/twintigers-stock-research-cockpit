package ca.twintigers.research.web;

import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.domain.portfolio.Allocation;
import ca.twintigers.research.domain.portfolio.Dividends;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.PerformanceCalculator;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ca.twintigers.research.web.Html.e;
import static ca.twintigers.research.web.Layout.card;

/** The Performance page: returns per period, value over time, allocation and dividends (what Ghostfolio shows today). */
final class PerformancePage {

    private PerformancePage() {
    }

    static String page(CockpitService.PerformanceView v) {
        if (v.error() != null) {
            return Layout.notice("The holdings source (" + v.source() + ") is not available: " + v.error());
        }
        String cur = v.baseCurrency();
        boolean own = v.returns().stream().anyMatch(r -> r.own().isPresent());
        StringBuilder out = new StringBuilder("<div class=\"cockpit-grid\">");

        // value over time
        out.append(card("Value over time", "Holdings value and money put in", "Daily, in " + cur + ", from the transactions and Yahoo "
                + "daily closes. Cash in another currency counts (its exchange-rate moves); " + cur + " cash does not. The gap between "
                + "the two lines is the gain.", "<div class=\"cockpit-chart-toolbar\"><label>Period <select id=\"perf-range\">"
                + "<option value=\"mtd\">Month to date</option><option value=\"ytd\">Year to date</option><option value=\"1y\">Last 12 months"
                + "</option><option value=\"max\" selected>Since the first transaction</option></select></label>"
                + "<span id=\"perf-summary\" class=\"muted small\"></span></div><div class=\"cockpit-chart-wrap\"><div id=\"perf-chart\" "
                + "class=\"cockpit-chart\" role=\"img\" aria-label=\"Portfolio value over time\"></div></div>", "chart-card full"));

        // returns table
        StringBuilder t = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Period</th><th>From – to</th>");
        if (own) {
            t.append("<th class=\"num\">").append(e(v.source())).append("</th>");
        }
        t.append("<th class=\"num\">Price return</th><th class=\"num\">Incl. dividends</th><th class=\"num\">Gain (").append(e(cur))
         .append(")</th><th class=\"num\">Dividends (").append(e(cur)).append(")</th><th class=\"num\">Value at end</th></tr></thead><tbody>");
        for (CockpitService.ReturnRow r : v.returns()) {
            PerformanceCalculator.Report c = r.computed();
            t.append("<tr><td>").append(e(r.label())).append("</td><td class=\"small\">")
             .append(c.priceReturnPct() == null ? "–" : e(c.from()) + " – " + e(c.to())).append("</td>");
            if (own) {
                t.append("<td class=\"num\">").append(r.own().map(Performance::netPerformancePct).map(PerformancePage::pct).orElse("–")).append("</td>");
            }
            t.append("<td class=\"num\">").append(pct(c.priceReturnPct())).append("</td><td class=\"num\">").append(pct(c.totalReturnPct()))
             .append("</td><td class=\"num ").append(c.gain().signum() < 0 ? "cockpit-neg" : "").append("\">").append(Html.money(c.gain()))
             .append("</td><td class=\"num\">").append(Html.money(c.dividends())).append("</td><td class=\"num\">")
             .append(Html.money(c.valueEnd())).append("</td></tr>");
        }
        t.append("</tbody></table></div>");
        List<String> notes = v.returns().stream().flatMap(r -> r.computed().notes().stream()).distinct().toList();
        if (!notes.isEmpty()) {
            t.append("<ul class=\"cockpit-list muted small\">");
            notes.forEach(n -> t.append("<li>").append(e(n)).append("</li>"));
            t.append("</ul>");
        }
        out.append(card("Returns", "By period", "Price return: time-weighted, excluding dividends (buys and sells do not distort it). "
                + (own ? "The " + v.source() + " column is its own figure (net performance with currency effect), for comparison. " : "")
                + "Past results say nothing about future ones.", t.toString(), "table-card full"));

        // allocation
        out.append(card("Allocation", "By holding", "Share of net worth (holdings and cash).", slices(v.byHolding(), cur), "table-card"));
        out.append(card("Allocation", "By currency", "Positions by trading currency, cash by account currency.", slices(v.byCurrency(), cur),
                "table-card"));
        out.append(card("Allocation", "By account", "Positions split by shares held, plus each account's cash.", slices(v.byAccount(), cur),
                "table-card"));

        // dividends
        out.append(card("Dividends", "Income by month", "Recorded DIVIDEND transactions, net of withholding, in " + cur + ".",
                dividends(v.dividends(), cur), "table-card"));
        out.append(card("Dividends", "Paid but not recorded", "Dividends Yahoo Finance reports on shares you held, with no dividend "
                + "transaction near the ex-date. Amounts are before withholding tax; check your statement, then record it.",
                missing(v.missing()), "table-card"));
        return out.append("</div>").toString();
    }

    static String pct(BigDecimal v) {
        if (v == null) {
            return "–";
        }
        String s = String.format(java.util.Locale.ROOT, "%+.2f%%", v.doubleValue());
        return "<span class=\"" + (v.signum() < 0 ? "cockpit-neg" : v.signum() > 0 ? "cockpit-pos" : "") + "\">" + s + "</span>";
    }

    static String slices(List<Allocation.Slice> list, String cur) {
        if (list.isEmpty()) {
            return "<p class=\"muted\">Nothing held.</p>";
        }
        StringBuilder b = new StringBuilder("<table class=\"cockpit-alloc\"><tbody>");
        for (Allocation.Slice s : list) {
            double w = s.pct() == null ? 0 : Math.max(0, Math.min(100, s.pct().doubleValue()));
            b.append("<tr><td>").append(e(s.name())).append("</td><td class=\"cockpit-alloc-bar\"><span style=\"width:")
             .append(String.format(java.util.Locale.ROOT, "%.1f", w)).append("%\"></span></td><td class=\"num\">")
             .append(s.pct() == null ? "–" : String.format(java.util.Locale.ROOT, "%.2f%%", s.pct().doubleValue())).append("</td><td class=\"num\">")
             .append(Html.money(s.valueInBase())).append(" ").append(e(cur)).append("</td></tr>");
        }
        return b.append("</tbody></table>").toString();
    }

    static String dividends(List<Dividends.Month> months, String cur) {
        if (months.isEmpty()) {
            return "<p class=\"muted\">No dividends recorded yet.</p>";
        }
        Map<Integer, BigDecimal> years = new TreeMap<>();
        months.forEach(m -> years.merge(m.month().getYear(), m.amountInBase(), BigDecimal::add));
        StringBuilder b = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Month</th><th class=\"num\">Payments</th>"
                + "<th class=\"num\">Income (" + e(cur) + ")</th></tr></thead><tbody>");
        for (Dividends.Month m : months.reversed()) {
            b.append("<tr><td>").append(e(m.month())).append("</td><td class=\"num\">").append(m.payments()).append("</td><td class=\"num\">")
             .append(Html.money(m.amountInBase())).append("</td></tr>");
        }
        b.append("</tbody><tfoot>");
        years.forEach((y, sum) -> b.append("<tr><th>").append(y).append(Year.now().getValue() == y ? " (so far)" : "")
                .append("</th><td></td><td class=\"num\"><strong>").append(Html.money(sum)).append("</strong></td></tr>"));
        return b.append("</tfoot></table></div>").toString();
    }

    static String missing(List<Dividends.Missing> list) {
        if (list.isEmpty()) {
            return "<p class=\"muted\">None found: every dividend on your holdings is recorded.</p>";
        }
        StringBuilder b = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Ex-date</th><th>Symbol</th><th>Account</th>"
                + "<th class=\"num\">Shares × per share</th><th class=\"num\">Amount</th><th></th></tr></thead><tbody>");
        for (Dividends.Missing m : list) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("type", "DIVIDEND");
            q.put("account", m.account());
            q.put("symbol", m.symbol());
            q.put("date", m.exDate().toString());
            q.put("quantity", m.shares().toPlainString());
            q.put("unitPrice", m.perShare().toPlainString());
            q.put("currency", m.currency());
            StringBuilder href = new StringBuilder("/transactions?");
            q.forEach((k, val) -> href.append(k).append('=').append(URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8)).append('&'));
            b.append("<tr><td>").append(e(m.exDate())).append("</td><td>").append(Pages.symbolLink(m.symbol())).append("</td><td>")
             .append(e(m.account())).append("</td><td class=\"num\">").append(e(m.shares().toPlainString())).append(" × ")
             .append(e(m.perShare().toPlainString())).append("</td><td class=\"num\">").append(Html.money(m.amount())).append(" ")
             .append(e(m.currency())).append("</td><td><a class=\"text-button\" href=\"").append(e(href.substring(0, href.length() - 1)))
             .append("\">Record…</a></td></tr>");
        }
        return b.append("</tbody></table></div>").toString();
    }

    /** JSON for the value chart. */
    static Map<String, Object> chart(PerformanceCalculator.Report r) {
        List<Map<String, Object>> value = new java.util.ArrayList<>();
        List<Map<String, Object>> invested = new java.util.ArrayList<>();
        for (PerformanceCalculator.Point p : r.points()) {
            value.add(Map.of("time", p.date().toString(), "value", p.value().doubleValue()));
            invested.add(Map.of("time", p.date().toString(), "value", p.netInvested().doubleValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r.range());
        out.put("from", String.valueOf(r.from()));
        out.put("to", String.valueOf(r.to()));
        out.put("value", value);
        out.put("invested", invested);
        out.put("priceReturnPct", r.priceReturnPct());
        out.put("gain", r.gain());
        out.put("colors", Map.of("value", ChartData.UP, "invested", ChartData.NEUTRAL, "text", "#12212e", "grid", "#d9e1e4"));
        return out;
    }
}
