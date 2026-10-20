package ca.twintigers.research.web;

import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.Allocation;
import ca.twintigers.research.domain.portfolio.Contributions;
import ca.twintigers.research.domain.portfolio.Fire;
import ca.twintigers.research.domain.portfolio.HoldingReturns;
import ca.twintigers.research.domain.portfolio.XRay;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static ca.twintigers.research.web.Html.e;
import static ca.twintigers.research.web.Layout.card;

/** The screens that were only in Ghostfolio: your position, analysis, more allocations, X-ray and FIRE. */
final class PortfolioScreens {

    private PortfolioScreens() {
    }

    // --- Stock Detail: your position ---------------------------------------------------------------------------

    static String position(String symbol, Optional<HoldingReturns.Holding> h, List<Activity> txs, String cur) {
        if (h.isEmpty() && txs.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        h.ifPresent(x -> {
            b.append("<section class=\"kpis cockpit-kpis\">")
             .append(Layout.kpi("Shares", Html.shares(x.quantity()), x.firstBuy() == null ? "" : "since " + x.firstBuy(), "accent"))
             .append(Layout.kpi("Average cost", x.avgCost() == null ? "–" : Html.money(x.avgCost()) + " " + e(x.currency()),
                     "per share, fees included", null))
             .append(Layout.kpi("Value", Html.money(x.value()) + " " + cur, "cost " + Html.money(x.costBasis()) + " " + cur, null))
             .append(Layout.kpi("Gain (not sold)", Html.money(x.unrealized()) + " " + cur, plainPct(x.unrealizedPct()),
                     x.unrealized().signum() < 0 ? "negative" : null))
             .append(Layout.kpi("Total return", Html.money(x.totalReturn()) + " " + cur, "incl. sold " + Html.money(x.realized())
                     + " and dividends " + Html.money(x.dividends()) + " · " + plainPct(x.totalReturnPct()), null))
             .append("</section>");
        });
        b.append("<div class=\"table-scroll\"><table><thead><tr><th>Date</th><th>Type</th><th>Account</th><th class=\"num\">Shares</th>"
                + "<th class=\"num\">Price</th><th class=\"num\">Fee</th><th>Currency</th><th>Comment</th></tr></thead><tbody>");
        for (Activity a : txs) {
            b.append("<tr><td>").append(e(a.date())).append("</td><td>").append(e(a.type().toLowerCase(Locale.ROOT))).append("</td><td>")
             .append(e(a.account())).append("</td><td class=\"num\">").append(Html.shares(a.quantity())).append("</td><td class=\"num\">")
             .append(Html.money(a.unitPrice())).append("</td><td class=\"num\">").append(Html.money(a.fee())).append("</td><td>")
             .append(e(a.currency())).append("</td><td class=\"small\">").append(e(a.comment() == null ? "" : a.comment())).append("</td></tr>");
        }
        b.append("</tbody></table></div><p class=\"muted small\"><a href=\"/transactions\">Edit these on the Transactions page</a>.</p>");
        return "<div class=\"cockpit-grid\">" + card("Your position", symbol, "From your transactions (average cost, after splits), valued "
                + "at the latest close; " + cur + " amounts use each trade's exchange rate for the cost and today's for the value.",
                b.toString(), "table-card full") + "</div>";
    }

    static String plainPct(BigDecimal v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%+.2f %%", v.doubleValue());
    }

    // --- Performance: analysis and more allocation ------------------------------------------------------------

    static String analysis(List<HoldingReturns.Holding> holdings, List<Contributions.Month> months, String cur) {
        StringBuilder out = new StringBuilder();
        out.append(card("Analysis", "Compared with the market", "Your time-weighted price return against the S&P/TSX Composite and the "
                + "S&P 500 over the same period (each in its own currency), in %. Use the Period menu above.",
                "<div class=\"cockpit-chart-wrap\"><div id=\"perf-bench\" class=\"cockpit-chart mk-history\" role=\"img\" "
                        + "aria-label=\"Return compared with the S&P/TSX and the S&P 500\"></div></div>", "chart-card full"));
        List<HoldingReturns.Holding> held = holdings.stream().filter(h -> h.quantity().signum() > 0 && h.totalReturnPct() != null).toList();
        List<HoldingReturns.Holding> best = held.stream().sorted(Comparator.comparing(HoldingReturns.Holding::totalReturnPct).reversed()).limit(3).toList();
        List<HoldingReturns.Holding> worst = held.stream().sorted(Comparator.comparing(HoldingReturns.Holding::totalReturnPct)).limit(3).toList();
        out.append(card("Analysis", "Best and worst holdings", "Total return since you bought (gain not sold + sold + dividends), "
                + "in % of what you paid.", performers(best, worst, cur), "table-card"));
        StringBuilder t = new StringBuilder();
        if (months.isEmpty()) {
            t.append("<p class=\"muted\">No buys or sells yet.</p>");
        } else {
            double max = months.stream().mapToDouble(m -> Math.abs(m.net().doubleValue())).max().orElse(1);
            t.append("<table class=\"cockpit-alloc\"><thead><tr><th>Month</th><th></th><th class=\"num\">Bought</th><th class=\"num\">Sold</th>"
                    + "<th class=\"num\">Net</th></tr></thead><tbody>");
            for (Contributions.Month m : months.reversed()) {
                double w = max == 0 ? 0 : Math.abs(m.net().doubleValue()) / max * 100;
                t.append("<tr><td>").append(e(m.month())).append("</td><td class=\"cockpit-alloc-bar\"><span class=\"")
                 .append(m.net().signum() < 0 ? "mk-fear" : "").append("\" style=\"width:").append(String.format(Locale.ROOT, "%.1f", w))
                 .append("%\"></span></td><td class=\"num\">").append(Html.money(m.bought())).append("</td><td class=\"num\">")
                 .append(Html.money(m.sold())).append("</td><td class=\"num\">").append(Html.money(m.net())).append("</td></tr>");
            }
            t.append("</tbody></table>");
        }
        out.append(card("Analysis", "Money put in by month", "Buys (fees included) minus sells, in " + cur + ".", t.toString(), "table-card"));
        return out.toString();
    }

    static String performers(List<HoldingReturns.Holding> best, List<HoldingReturns.Holding> worst, String cur) {
        if (best.isEmpty()) {
            return "<p class=\"muted\">No holdings yet.</p>";
        }
        StringBuilder b = new StringBuilder("<table class=\"cockpit-alloc\"><tbody><tr><th colspan=\"3\">Best</th></tr>");
        best.forEach(h -> row(b, h, cur));
        b.append("<tr><th colspan=\"3\">Worst</th></tr>");
        worst.forEach(h -> row(b, h, cur));
        return b.append("</tbody></table>").toString();
    }

    private static void row(StringBuilder b, HoldingReturns.Holding h, String cur) {
        b.append("<tr><td>").append(Pages.symbolLink(h.symbol())).append("</td><td class=\"num\">").append(PerformancePage.pct(h.totalReturnPct()))
         .append("</td><td class=\"num\">").append(Html.money(h.totalReturn())).append(" ").append(e(cur)).append("</td></tr>");
    }

    static String moreAllocation(List<Allocation.Slice> sector, List<Allocation.Slice> region, List<Allocation.Slice> country,
                                 List<Allocation.Slice> assetClass, Set<String> estimated, String cur) {
        String note = estimated.isEmpty() ? "" : " Estimated from Yahoo's listing data for " + String.join(", ", estimated.stream().sorted().toList()) + ".";
        return card("Allocation", "By asset class", "Stocks, ETFs, cash." + note, PerformancePage.slices(assetClass, cur), "table-card")
                + card("Allocation", "By sector", "An ETF counts by its sector weights." + note, PerformancePage.slices(sector, cur), "table-card")
                + card("Allocation", "By region", "Where the companies are based." + note, PerformancePage.slices(region, cur), "table-card")
                + card("Allocation", "By country", "Where the companies are based." + note, PerformancePage.slices(country, cur), "table-card");
    }

    // --- X-ray ----------------------------------------------------------------------------------------------------

    static String xray(CockpitService.XRayView v) {
        if (v.error() != null) {
            return Layout.notice("The holdings source is not available: " + v.error());
        }
        long warn = v.checks().stream().filter(c -> c.status() == XRay.Status.WARN).count();
        StringBuilder b = new StringBuilder("<p>").append(warn == 0 ? "All checks within their limits." : warn + " of " + v.checks().size()
                + " checks are outside their limits.").append("</p><div class=\"table-scroll\"><table><thead><tr><th></th><th>Area</th>"
                + "<th>Check</th><th>Finding</th><th>Limit</th></tr></thead><tbody>");
        for (XRay.Check c : v.checks()) {
            String badge = switch (c.status()) {
                case OK -> Layout.badge("within limit", "success");
                case WARN -> Layout.badge("outside limit", "warning");
                case NOT_SET -> Layout.badge("not set up", "watch");
            };
            b.append("<tr><td>").append(badge).append("</td><td>").append(e(c.category())).append("</td><td>").append(e(c.name()))
             .append("</td><td>").append(e(c.finding())).append("</td><td class=\"small\">").append(e(c.threshold())).append("</td></tr>");
        }
        b.append("</tbody></table></div>");
        if (!v.estimated().isEmpty()) {
            b.append("<p class=\"muted small\">Sector and region for ").append(e(String.join(", ", v.estimated().stream().sorted().toList())))
             .append(" are estimated from Yahoo's listing data.</p>");
        }
        return "<div class=\"cockpit-grid\">" + card("X-ray", "Portfolio checks", "Like Ghostfolio's X-ray: each line compares one share of "
                + "your portfolio with a limit you can change in java\\config\\rules.properties (xray.*, then restart the Cockpit). They "
                + "describe how the portfolio is made up; they are not advice.", b.toString(), "table-card full") + "</div>";
    }

    // --- FIRE -----------------------------------------------------------------------------------------------------

    record FireInput(double savings, double returnPct, double ratePct, double spending, int years) {
    }

    static String fire(Fire.Result r, FireInput in, String cur) {
        StringBuilder f = new StringBuilder("<form class=\"cockpit-form\" action=\"/fire\" method=\"get\">")
                .append(num("Yearly savings (" + cur + ")", "savings", in.savings(), "1000"))
                .append(num("Real return per year (%)", "return", in.returnPct(), "0.1"))
                .append(num("Withdrawal rate (%)", "rate", in.ratePct(), "0.1"))
                .append(num("Yearly spending in retirement (" + cur + ")", "spending", in.spending(), "1000"))
                .append(num("Years to project", "years", in.years(), "1"))
                .append("<button type=\"submit\" class=\"primary-button\">Calculate</button></form>");
        StringBuilder k = new StringBuilder("<section class=\"kpis cockpit-kpis\">")
                .append(Layout.kpi("Net worth today", Html.money(r.netWorth()) + " " + cur, "holdings and cash", "accent"))
                .append(Layout.kpi("Withdrawal per year", Html.money(r.withdrawalYear()) + " " + cur, num(in.ratePct()) + " % of net worth", null))
                .append(Layout.kpi("Withdrawal per month", Html.money(r.withdrawalMonth()) + " " + cur, "the same, by month", null));
        if (r.target() != null) {
            k.append(Layout.kpi("Net worth needed", Html.money(r.target()) + " " + cur, "for " + Html.money(BigDecimal.valueOf(in.spending()))
                    + " a year", null))
             .append(Layout.kpi("Years to get there", r.yearsToTarget() == null ? "beyond " + in.years() : String.valueOf(r.yearsToTarget()),
                     r.yearsToTarget() != null && r.yearsToTarget() == 0 ? "already there" : "with the savings and return above", null));
        }
        k.append("</section>");
        StringBuilder t = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th>Year</th><th class=\"num\">Net worth (" + e(cur)
                + ")</th><th class=\"num\">Withdrawal per year</th></tr></thead><tbody>");
        for (Fire.Year y : r.projection()) {
            boolean hit = r.target() != null && y.netWorth().compareTo(r.target()) >= 0;
            t.append("<tr").append(hit ? " class=\"fire-hit\"" : "").append("><td>").append(y.year()).append("</td><td class=\"num\">")
             .append(Html.money(y.netWorth())).append("</td><td class=\"num\">").append(Html.money(y.yearlyWithdrawal())).append("</td></tr>");
        }
        t.append("</tbody></table></div>");
        return "<div class=\"cockpit-grid\">" + card("FIRE", "Financial independence", "Like Ghostfolio's FIRE page: the withdrawal rate "
                + "applied to today's net worth, and a projection with yearly savings at an assumed return after inflation. These are "
                + "assumptions, not predictions; your Twintiger Retirement Planner models taxes, pensions and spending in detail.",
                f.toString() + k + t, "table-card full") + "</div>";
    }

    private static String num(String label, String name, double value, String step) {
        return "<label>" + e(label) + " <input type=\"number\" name=\"" + name + "\" step=\"" + step + "\" value=\"" + num(value) + "\"></label>";
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
