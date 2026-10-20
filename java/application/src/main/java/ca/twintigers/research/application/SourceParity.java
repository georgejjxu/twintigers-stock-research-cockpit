package ca.twintigers.research.application;

import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.PerformanceCalculator;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.PortfolioSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Compares the local ledger (candidate) with Ghostfolio (reference) before the switch: accounts and cash, shares per
 * account, transactions, market values, and returns (Ghostfolio's own figure against the Java calculation).
 * Exact items must match exactly; values and returns within a tolerance, because the two use different price
 * snapshots (Ghostfolio's delayed quote vs the latest Yahoo daily bar) and exchange rates.
 */
public final class SourceParity {

    public static final double VALUE_TOLERANCE_PCT = 1.0;
    public static final double RETURN_TOLERANCE_PP = 0.25;

    public enum Status { MATCH, WITHIN_TOLERANCE, DIFFERENT }

    public record Line(String area, String item, String reference, String candidate, Status status, String note) {
    }

    public record Result(List<Line> lines, String markdown) {
        public long differences() {
            return lines.stream().filter(l -> l.status() == Status.DIFFERENT).count();
        }
    }

    private final Clock clock;

    public SourceParity(Clock clock) {
        this.clock = clock;
    }

    public Result compare(PortfolioSource reference, PortfolioSource candidate, PerformanceService candidateReturns,
                          List<String> ranges) throws DataSourceException {
        List<Line> lines = new ArrayList<>();

        // accounts and cash, in each account's currency
        Map<String, Account> ra = byName(reference.accounts());
        Map<String, Account> ca = byName(candidate.accounts());
        for (String name : new TreeSet<>(union(ra.keySet(), ca.keySet()))) {
            Account r = ra.get(name);
            Account c = ca.get(name);
            String rv = r == null ? "missing" : plain(r.cashBalance()) + " " + r.currency();
            String cv = c == null ? "missing" : plain(c.cashBalance()) + " " + c.currency();
            lines.add(new Line("Accounts and cash", name, rv, cv, rv.equals(cv) ? Status.MATCH : Status.DIFFERENT, ""));
        }

        // transactions
        List<Activity> rActs = reference.activities();
        List<Activity> cActs = candidate.activities();
        for (String type : List.of("BUY", "SELL", "DIVIDEND")) {
            long rn = rActs.stream().filter(a -> type.equalsIgnoreCase(a.type())).count();
            long cn = cActs.stream().filter(a -> type.equalsIgnoreCase(a.type())).count();
            lines.add(new Line("Transactions", type.toLowerCase(java.util.Locale.ROOT) + " count", String.valueOf(rn), String.valueOf(cn),
                    rn == cn ? Status.MATCH : Status.DIFFERENT, ""));
        }
        long others = rActs.stream().filter(a -> !List.of("BUY", "SELL", "DIVIDEND").contains(a.type())).count();
        if (others > 0) {
            lines.add(new Line("Transactions", "other types", String.valueOf(others), "0", Status.DIFFERENT,
                    "fees, interest and other types are not kept in the ledger"));
        }

        // holdings: shares per account exactly, value within tolerance
        Portfolio rp = reference.portfolio();
        Portfolio cp = candidate.portfolio();
        Map<String, Portfolio.Position> rpos = bySymbol(rp);
        Map<String, Portfolio.Position> cpos = bySymbol(cp);
        for (String s : new TreeSet<>(union(rpos.keySet(), cpos.keySet()))) {
            Portfolio.Position r = rpos.get(s);
            Portfolio.Position c = cpos.get(s);
            String rs = r == null ? "missing" : shares(r.sharesByAccount());
            String cs = c == null ? "missing" : shares(c.sharesByAccount());
            lines.add(new Line("Shares per account", s, rs, cs, rs.equals(cs) ? Status.MATCH : Status.DIFFERENT, ""));
            if (r != null && c != null) {
                lines.add(value("Market value (" + rp.baseCurrency() + ")", s, r.valueInBase(), c.valueInBase(),
                        "price " + price(r.marketPrice()) + " vs " + price(c.marketPrice()) + " " + r.currency()));
            }
        }
        lines.add(value("Market value (" + rp.baseCurrency() + ")", "all holdings", rp.investedInBase(), cp.investedInBase(), ""));
        lines.add(value("Market value (" + rp.baseCurrency() + ")", "cash", rp.cashInBase(), cp.cashInBase(), "exchange rates differ slightly"));

        // returns: Ghostfolio's figure vs the Java calculation on the ledger
        for (String range : ranges) {
            Optional<Performance> r = reference.performance(range);
            PerformanceCalculator.Report c = candidateReturns.report(range);
            BigDecimal rpct = r.map(Performance::netPerformancePct).orElse(null);
            BigDecimal cpct = c.priceReturnPct();
            Status st;
            java.time.LocalDate today = java.time.LocalDate.now(clock);
            boolean dayBoundary = range.equals("1d") && (!today.equals(java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)))
                    || !today.equals(c.to()));
            if (dayBoundary) {
                lines.add(new Line("Return (%)", range, rpct == null ? "–" : rpct.setScale(2, RoundingMode.HALF_EVEN) + " %",
                        cpct == null ? "–" : cpct.setScale(2, RoundingMode.HALF_EVEN) + " %", Status.WITHIN_TOLERANCE,
                        "not comparable now: the Java figure is the latest trading session (" + c.from() + "), Ghostfolio's is since "
                                + "its last midnight UTC (8 pm Eastern); they line up on trading days between the close and 8 pm, when the "
                                + "nightly check runs"));
                continue;
            }
            if (rpct == null || cpct == null) {
                st = rpct == cpct ? Status.MATCH : Status.DIFFERENT;
            } else {
                double diff = Math.abs(rpct.doubleValue() - cpct.doubleValue());
                st = diff < 0.005 ? Status.MATCH : diff <= RETURN_TOLERANCE_PP ? Status.WITHIN_TOLERANCE : Status.DIFFERENT;
            }
            lines.add(new Line("Return (%)", range, rpct == null ? "–" : rpct.setScale(2, RoundingMode.HALF_EVEN) + " %",
                    cpct == null ? "–" : cpct.setScale(2, RoundingMode.HALF_EVEN) + " %", st,
                    "gain " + r.map(Performance::netPerformance).map(SourceParity::money).orElse("–") + " vs " + money(c.gain())
                            + (c.notes().isEmpty() ? "" : "; " + String.join(" ", c.notes()))));
        }
        List<String> order = lines.stream().map(Line::area).distinct().toList();
        List<Line> grouped = lines.stream().sorted(java.util.Comparator.comparingInt(l -> order.indexOf(l.area()))).toList();
        return new Result(grouped, markdown(grouped, reference.name(), candidate.name()));
    }

    private static Line value(String area, String item, BigDecimal r, BigDecimal c, String note) {
        Status st;
        if (r.compareTo(c) == 0) {
            st = Status.MATCH;
        } else if (r.signum() == 0) {
            st = Status.DIFFERENT;
        } else {
            double pct = Math.abs(c.doubleValue() - r.doubleValue()) / Math.abs(r.doubleValue()) * 100;
            st = pct <= VALUE_TOLERANCE_PCT ? Status.WITHIN_TOLERANCE : Status.DIFFERENT;
        }
        return new Line(area, item, money(r), money(c), st, note);
    }

    private String markdown(List<Line> lines, String ref, String cand) {
        StringBuilder b = new StringBuilder("# Ghostfolio parity: is the Java ledger ready to replace Ghostfolio?\n\n");
        long diff = lines.stream().filter(l -> l.status() == Status.DIFFERENT).count();
        b.append("Generated ").append(ZonedDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")))
         .append(" by `research-cli ghostfolio-parity` (also nightly by the monitor). Reference: **").append(ref)
         .append("**; candidate: **").append(cand).append("** (the Java ledger, mirrored from Ghostfolio by `ledger-sync`).\n\n")
         .append(diff == 0 ? "**Result: no differences beyond the tolerances.**\n\n" : "**Result: " + diff + " differences.**\n\n")
         .append("Exact items must match exactly. Market values may differ by up to ").append(VALUE_TOLERANCE_PCT)
         .append(" % (different price snapshots and exchange rates); returns by up to ").append(RETURN_TOLERANCE_PP)
         .append(" percentage points. Ghostfolio's return is its net performance with currency effect; the Java figure is a ")
         .append("time-weighted price return (excluding dividends) computed from the ledger and Yahoo daily closes. For a ")
         .append("portfolio bought at once they are the same thing; with later buys and sells they can legitimately differ. ")
         .append("Both count cash in a foreign currency (its exchange-rate moves) and leave base-currency cash out.\n\n");
        String area = null;
        for (Line l : lines) {
            if (!l.area().equals(area)) {
                area = l.area();
                b.append("\n## ").append(area).append("\n\n| Item | ").append(ref).append(" | ").append(cand).append(" | Status | Note |\n|---|---|---|---|---|\n");
            }
            b.append("| ").append(l.item()).append(" | ").append(l.reference()).append(" | ").append(l.candidate()).append(" | ")
             .append(switch (l.status()) {
                 case MATCH -> "match";
                 case WITHIN_TOLERANCE -> "within tolerance";
                 case DIFFERENT -> "**different**";
             }).append(" | ").append(l.note().replace("|", "/")).append(" |\n");
        }
        return b.toString();
    }

    private static Map<String, Account> byName(List<Account> accounts) {
        Map<String, Account> m = new LinkedHashMap<>();
        accounts.forEach(a -> m.put(a.name(), a));
        return m;
    }

    private static Map<String, Portfolio.Position> bySymbol(Portfolio p) {
        Map<String, Portfolio.Position> m = new LinkedHashMap<>();
        p.positions().forEach(x -> m.put(x.symbol(), x));
        return m;
    }

    private static List<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        List<String> out = new ArrayList<>(a);
        b.stream().filter(x -> !a.contains(x)).forEach(out::add);
        return out;
    }

    private static String shares(Map<String, BigDecimal> byAccount) {
        return new java.util.TreeMap<>(byAccount).entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .map(e -> e.getKey() + ": " + plain(e.getValue())).reduce((x, y) -> x + "; " + y).orElse("none");
    }

    static String plain(BigDecimal v) {
        return v == null ? "–" : v.stripTrailingZeros().toPlainString();
    }

    static String price(BigDecimal v) {
        return v == null ? "–" : plain(v.setScale(4, RoundingMode.HALF_EVEN));
    }

    static String money(BigDecimal v) {
        return v == null ? "–" : String.format(java.util.Locale.ROOT, "%,.2f", v);
    }
}
