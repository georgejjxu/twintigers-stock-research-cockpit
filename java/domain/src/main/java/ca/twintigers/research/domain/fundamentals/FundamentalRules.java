package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.fundamentals.HealthTable.Column;
import ca.twintigers.research.domain.fundamentals.HealthTable.Kind;
import ca.twintigers.research.domain.provenance.Cell;
import ca.twintigers.research.domain.rules.Flag;
import ca.twintigers.research.domain.rules.Severity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ca.twintigers.research.domain.fundamentals.HealthTableBuilder.*;

/**
 * Rule-based warning signs on the latest fiscal year (and year-to-date where it applies).
 * F01–F07 are ported from research_core/health.py with the same wording; F08–F11 are new
 * (see docs/architecture/rule-catalog.md).
 */
public final class FundamentalRules {

    private final Thresholds t;

    public FundamentalRules(Thresholds thresholds) {
        this.t = thresholds;
    }

    /** Python-compatible number formatting: the exact binary value rounded half-even. */
    static String fmt(double v, int decimals) {
        return new BigDecimal(v).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
    }

    static String millions(double v) {
        return String.format(java.util.Locale.ROOT, "%,.0f", v / 1e6);
    }

    public List<Flag> evaluate(Map<String, List<Cell>> rows, List<Column> cols, boolean bank) {
        List<Integer> fy = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).kind() == Kind.FY) {
                fy.add(i);
            }
        }
        List<Flag> flags = new ArrayList<>();
        if (fy.isEmpty()) {
            return flags;
        }
        int last = fy.getLast();
        Integer prev = fy.size() > 1 ? fy.get(fy.size() - 2) : null;
        boolean hasYtd = cols.getLast().kind() == Kind.YTD;
        int ytdCur = cols.size() - 1;
        int ytdPrior = cols.size() - 2;

        Double g = val(rows, REVENUE_GROWTH, last);
        if (g != null && g < 0) {
            add(flags, "F01_revenue_decline", Severity.WARNING,
                    "Revenue declined " + fmt(Math.abs(g), 1) + "% in the latest fiscal year.", "revenue_growth_pct", g);
        }
        if (hasYtd) {
            Double gy = val(rows, REVENUE_GROWTH, ytdCur);
            if (gy != null && gy < 0) {
                add(flags, "F02_ytd_revenue_decline", Severity.WARNING,
                        "Year-to-date revenue is down " + fmt(Math.abs(gy), 1) + "% on the prior year.", "ytd_revenue_growth_pct", gy);
            }
        }
        Double m1 = val(rows, OPERATING_MARGIN, last);
        Double m0 = val(rows, OPERATING_MARGIN, prev);
        if (m1 != null && m0 != null && m0 - m1 > t.operatingMarginDropPts()) {
            add(flags, "F03_margin_drop", Severity.WARNING, "Operating margin fell " + fmt(m0 - m1, 1) + " points ("
                    + fmt(m0, 1) + "% to " + fmt(m1, 1) + "%).", "margin_prior_pct", m0, "margin_latest_pct", m1,
                    "threshold_pts", t.operatingMarginDropPts());
        }
        Double fcf = val(rows, FCF, last);
        if (fcf != null && fcf < 0) {
            add(flags, "F04_negative_fcf", Severity.WARNING, "Free cash flow was negative in the latest fiscal year.",
                    "fcf", fcf);
        }
        Double cc = val(rows, CASH_CONVERSION, last);
        Double ni = val(rows, NET_INCOME, last);
        if (!bank && cc != null && ni != null && ni > 0 && cc < t.cashConversionMin()) {
            add(flags, "F05_low_cash_conversion", Severity.WATCH, "Operating cash flow was only " + fmt(cc, 2)
                            + "× net income; earnings may be running ahead of cash.", "cash_conversion", cc,
                    "threshold", t.cashConversionMin());
        }
        Double dil = val(rows, DILUTION, last);
        if (dil != null && dil > t.dilutionMaxPct()) {
            add(flags, "F06_dilution", Severity.WATCH, "Diluted share count grew " + fmt(dil, 1) + "% (threshold "
                    + t.dilutionMaxPct() + "%).", "dilution_pct", dil, "threshold_pct", t.dilutionMaxPct());
        }
        Double d1 = val(rows, TOTAL_DEBT, last);
        Double d0 = val(rows, TOTAL_DEBT, prev);
        Double c1 = val(rows, CASH, last);
        Double c0 = val(rows, CASH, prev);
        if (d1 != null && d0 != null && c1 != null && c0 != null && d0 > 0
                && (d1 / d0 - 1) * 100 > t.debtRisePct() && c1 < c0) {
            add(flags, "F07_debt_up_cash_down", Severity.WARNING, "Debt rose " + fmt((d1 / d0 - 1) * 100, 0)
                    + "% while cash fell.", "debt_prior", d0, "debt_latest", d1, "cash_prior", c0, "cash_latest", c1);
        }

        // --- new rules -------------------------------------------------------------------------------
        Double f0 = val(rows, FCF, prev);
        if (fcf != null && f0 != null && f0 > 0) {
            double drop = (1 - fcf / f0) * 100;
            if (drop > t.fcfDropPct()) {
                add(flags, "F08_fcf_drop", Severity.WARNING, "Free cash flow fell " + fmt(drop, 0) + "% in the latest "
                                + "fiscal year (" + millions(f0) + " to " + millions(fcf) + " millions).",
                        "fcf_prior", f0, "fcf_latest", fcf, "drop_pct", drop, "threshold_pct", t.fcfDropPct());
            }
        }
        if (hasYtd) {
            Double y1 = val(rows, FCF, ytdCur);
            Double y0 = val(rows, FCF, ytdPrior);
            if (y1 != null && y0 != null && y0 > 0) {
                double drop = (1 - y1 / y0) * 100;
                if (drop > t.fcfDropPct()) {
                    add(flags, "F09_ytd_fcf_drop", Severity.WARNING, "Year-to-date free cash flow is down " + fmt(drop, 0)
                                    + "% on the prior year (" + millions(y0) + " to " + millions(y1) + " millions).",
                            "ytd_fcf_prior", y0, "ytd_fcf_latest", y1, "drop_pct", drop, "threshold_pct", t.fcfDropPct());
                }
            }
        }
        int declines = 0;
        for (int k = fy.size() - 1; k > 0; k--) {
            Double cur = val(rows, FCF, fy.get(k));
            Double before = val(rows, FCF, fy.get(k - 1));
            if (cur == null || before == null || before == 0 || cur >= before
                    || (before - cur) / Math.abs(before) * 100 < t.fcfDeclineMinPct()) {
                break;
            }
            declines++;
        }
        if (declines >= t.fcfDeclineYears()) {
            List<String> series = new ArrayList<>();
            Map<String, Object> ev = new LinkedHashMap<>();
            for (int k = fy.size() - 1 - declines; k < fy.size(); k++) {
                Double v = val(rows, FCF, fy.get(k));
                series.add(millions(v));
                ev.put("fcf_" + cols.get(fy.get(k)).period().end().getYear(), v);
            }
            ev.put("years_declining", declines);
            ev.put("min_decline_pct_per_year", t.fcfDeclineMinPct());
            flags.add(new Flag("F10_fcf_declining_years", Severity.WATCH, "Free cash flow has fallen " + declines
                    + " years in a row (" + String.join(" → ", series) + " millions).", ev));
        }
        Double n1 = val(rows, NET_DEBT, last);
        Double n0 = val(rows, NET_DEBT, prev);
        if (n1 != null && n0 != null && n0 > 0) {
            double rise = (n1 / n0 - 1) * 100;
            if (rise > t.netDebtRisePct()) {
                add(flags, "F11_net_debt_rise", Severity.WATCH, "Net debt rose " + fmt(rise, 0) + "% in the latest fiscal "
                                + "year (" + millions(n0) + " to " + millions(n1) + " millions).",
                        "net_debt_prior", n0, "net_debt_latest", n1, "rise_pct", rise, "threshold_pct", t.netDebtRisePct());
            }
        }
        return flags;
    }

    private static Double val(Map<String, List<Cell>> rows, String name, Integer i) {
        if (i == null) {
            return null;
        }
        Cell c = rows.get(name).get(i);
        return c == null || c.value() == null ? null : c.value().doubleValue();
    }

    private static void add(List<Flag> flags, String id, Severity s, String text, Object... kv) {
        Map<String, Object> ev = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            ev.put((String) kv[i], kv[i + 1]);
        }
        flags.add(new Flag(id, s, text, ev));
    }
}
