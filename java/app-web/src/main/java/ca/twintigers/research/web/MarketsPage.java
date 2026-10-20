package ca.twintigers.research.web;

import ca.twintigers.research.application.MarketService;
import ca.twintigers.research.domain.market.Sentiment;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static ca.twintigers.research.web.Html.e;
import static ca.twintigers.research.web.Layout.card;

/** The Markets page: Fear &amp; Greed (gauge, components, history) and benchmark indices. */
final class MarketsPage {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'ET'");

    private MarketsPage() {
    }

    static String page(MarketService.View v) {
        StringBuilder out = new StringBuilder("<div class=\"cockpit-grid\">");
        if (v.sentiment().isEmpty()) {
            out.append(card("Sentiment", "Fear & Greed Index", null, Layout.notice("Not available right now: " + v.sentimentError()),
                    "table-card full"));
        }
        for (int i = 0; i < v.sentiment().size(); i++) {
            Sentiment s = v.sentiment().get(i);
            boolean main = i == 0;
            StringBuilder b = new StringBuilder("<div class=\"mk-sentiment\"><div class=\"mk-gauge-box\">").append(gauge(s.score(), s.rating()))
                    .append("<p class=\"muted small\">").append(e(s.source())).append(", ")
                    .append(e(s.asOf().atZone(ZoneId.of("America/Toronto")).format(WHEN))).append("</p></div><div class=\"mk-previous\">");
            b.append("<table class=\"cockpit-explain-facts\"><tbody>");
            for (Sentiment.Reading r : s.previous()) {
                b.append("<tr><th>").append(e(r.label())).append("</th><td class=\"num\">").append(fmt(r.score())).append("</td><td>")
                 .append(ratingBadge(r.rating())).append("</td></tr>");
            }
            b.append("</tbody></table></div></div>");
            if (!s.components().isEmpty()) {
                b.append("<h3 class=\"cockpit-subhead\">What goes into it</h3><table class=\"cockpit-alloc\"><tbody>");
                for (Sentiment.Reading r : s.components()) {
                    b.append("<tr><td>").append(e(r.label())).append("</td><td class=\"cockpit-alloc-bar mk-bar\"><span class=\"")
                     .append(bandClass(r.score())).append("\" style=\"width:").append(String.format(Locale.ROOT, "%.1f", Math.max(1, r.score())))
                     .append("%\"></span></td><td class=\"num\">").append(fmt(r.score())).append("</td><td>").append(ratingBadge(r.rating()))
                     .append("</td></tr>");
                }
                b.append("</tbody></table>");
            }
            StringBuilder pts = new StringBuilder("[");
            for (Sentiment.Point p : s.history()) {
                if (pts.length() > 1) {
                    pts.append(',');
                }
                pts.append("{\"time\":\"").append(p.date()).append("\",\"value\":").append(p.score()).append('}');
            }
            pts.append(']');
            b.append("<h3 class=\"cockpit-subhead\">The last year</h3><div class=\"mk-history\" data-points=\"").append(e(pts.toString()))
             .append("\" role=\"img\" aria-label=\"").append(e(s.name())).append(" over the last year\"></div>");
            out.append(card("Sentiment", s.name(), main
                    ? "0 = extreme fear, 100 = extreme greed. CNN combines seven market measures, each scored against its own history. "
                    + "It describes the mood of the US stock market; it is not a forecast."
                    : "Same scale, for crypto-currencies (volatility, volume, social media, dominance, trends).", b.toString(),
                    main ? "table-card full" : "table-card full"));
        }

        StringBuilder t = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Market</th><th>Symbol</th><th>As of</th>"
                + "<th class=\"num\">Last</th><th class=\"num\">Day</th><th class=\"num\">Year to date</th><th class=\"num\">12 months</th>"
                + "<th class=\"num\">From 52-week high</th><th>50-day avg</th><th>200-day avg</th></tr></thead><tbody>");
        for (MarketService.Benchmark m : v.benchmarks()) {
            t.append("<tr><td>").append(e(m.name())).append("</td><td>").append(Pages.symbolLink(m.symbol())).append("</td>");
            if (m.error() != null) {
                t.append("<td colspan=\"8\" class=\"muted small\">").append(e(m.error())).append("</td></tr>");
                continue;
            }
            t.append("<td class=\"small\">").append(e(m.date())).append("</td><td class=\"num\">").append(Html.money(m.close()))
             .append("</td><td class=\"num\">").append(PerformancePage.pct(dec(m.dayPct()))).append("</td><td class=\"num\">")
             .append(PerformancePage.pct(dec(m.ytdPct()))).append("</td><td class=\"num\">").append(PerformancePage.pct(dec(m.yearPct())))
             .append("</td><td class=\"num\">").append(PerformancePage.pct(dec(m.fromHighPct()))).append("</td><td>")
             .append(trend(m.above50(), m.sma50())).append("</td><td>").append(trend(m.above200(), m.sma200())).append("</td></tr>");
        }
        t.append("</tbody></table></div>");
        out.append(card("Benchmarks", "Markets at a glance", "Daily closes (" + v.source() + "). \"Above\" means the last close is above "
                + "that moving average. Click a symbol for its chart.", t.toString(), "table-card full"));
        return out.append("</div>").toString();
    }

    static java.math.BigDecimal dec(Double v) {
        return v == null ? null : java.math.BigDecimal.valueOf(v);
    }

    static String trend(Boolean above, Double avg) {
        if (above == null) {
            return "<span class=\"muted\">–</span>";
        }
        return "<span class=\"" + (above ? "cockpit-pos" : "cockpit-neg") + "\">" + (above ? "above" : "below") + "</span> <span class=\"muted small\">"
                + Html.money(avg) + "</span>";
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    static String bandClass(double score) {
        return "mk-" + Sentiment.band(score).replace(' ', '-');
    }

    static String ratingBadge(String rating) {
        String r = rating == null ? "" : rating.toLowerCase(Locale.ROOT);
        return "<span class=\"mk-rating mk-" + e(r.replace(' ', '-')) + "\">" + e(r) + "</span>";
    }

    /** A half-circle gauge with the five bands and a needle at the score. */
    static String gauge(double score, String rating) {
        double cx = 120, cy = 110, r = 90;
        String[][] bands = {{"0", "25", "#c0392b"}, {"25", "45", "#e67e22"}, {"45", "55", "#b8b8b8"}, {"55", "75", "#7fb069"}, {"75", "100", "#2e8b57"}};
        StringBuilder s = new StringBuilder("<svg class=\"mk-gauge\" viewBox=\"0 0 240 140\" role=\"img\" aria-label=\"Score "
                + fmt(score) + " out of 100: " + e(rating) + "\">");
        for (String[] b : bands) {
            double a0 = Math.PI * (1 - Double.parseDouble(b[0]) / 100);
            double a1 = Math.PI * (1 - Double.parseDouble(b[1]) / 100);
            s.append(String.format(Locale.ROOT, "<path d=\"M %.1f %.1f A %.0f %.0f 0 0 1 %.1f %.1f\" stroke=\"%s\" stroke-width=\"18\" fill=\"none\"/>",
                    cx + r * Math.cos(a0), cy - r * Math.sin(a0), r, r, cx + r * Math.cos(a1), cy - r * Math.sin(a1), b[2]));
        }
        double a = Math.PI * (1 - Math.max(0, Math.min(100, score)) / 100);
        s.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#12212e\" stroke-width=\"3\" "
                + "stroke-linecap=\"round\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"5\" fill=\"#12212e\"/>", cx, cy, cx + (r - 22) * Math.cos(a),
                cy - (r - 22) * Math.sin(a), cx, cy));
        s.append(String.format(Locale.ROOT, "<text x=\"%.0f\" y=\"%.0f\" text-anchor=\"middle\" class=\"mk-score\">%s</text>", cx, cy + 26, fmt(score)));
        s.append("<text x=\"18\" y=\"132\" class=\"mk-end\">0</text><text x=\"222\" y=\"132\" text-anchor=\"end\" class=\"mk-end\">100</text>");
        return s.append("</svg><p class=\"mk-gauge-label\">").append(ratingBadge(rating)).append("</p>").toString();
    }
}
