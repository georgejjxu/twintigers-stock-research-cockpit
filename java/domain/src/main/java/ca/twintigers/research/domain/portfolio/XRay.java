package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Portfolio checks in the spirit of Ghostfolio's X-ray: each compares one share of the portfolio with a threshold
 * The user can change (xray.* in rules.properties) and says what it found. They describe the portfolio's make-up;
 * they are not advice.
 */
public final class XRay {

    private XRay() {
    }

    public enum Status { OK, WARN, NOT_SET }

    public record Check(String category, String name, Status status, String finding, String threshold) {
    }

    /**
     * @param maxSinglePct      largest single issuer (all listings) as % of net worth
     * @param maxAccountPct     largest account as % of invested (holdings)
     * @param minBaseCurrencyPct base-currency share of invested at least
     * @param maxCurrencyPct    any one currency's share of invested at most
     * @param maxSectorPct      any one sector's share of invested at most
     * @param maxRegionPct      any one region's share of invested at most
     * @param maxFeePct         fees paid as % of everything bought
     * @param emergencyFund     cash to keep aside, in the base currency (0 = not set up)
     */
    public record Limits(double maxSinglePct, double maxAccountPct, double minBaseCurrencyPct, double maxCurrencyPct,
                         double maxSectorPct, double maxRegionPct, double maxFeePct, double emergencyFund) {

        public static Limits fromMap(Map<String, String> m) {
            return new Limits(d(m, "xray.maxSinglePct", 10), d(m, "xray.maxAccountPct", 50), d(m, "xray.minBaseCurrencyPct", 50),
                    d(m, "xray.maxCurrencyPct", 50), d(m, "xray.maxSectorPct", 35), d(m, "xray.maxRegionPct", 80), d(m, "xray.maxFeePct", 1),
                    d(m, "xray.emergencyFund", 0));
        }

        static double d(Map<String, String> m, String k, double def) {
            String v = m.get(k);
            return v == null || v.isBlank() ? def : Double.parseDouble(v.strip());
        }
    }

    public static List<Check> run(Portfolio p, List<Account> accounts, Map<String, InstrumentProfile> profiles, BigDecimal feesPaid,
                                  BigDecimal everythingBought, Limits l) {
        List<Check> out = new ArrayList<>();
        String cur = p.baseCurrency();
        double invested = p.investedInBase().doubleValue();

        // concentration: issuer
        List<Portfolio.IssuerWeight> issuers = p.issuerWeights(l.maxSinglePct());
        if (!issuers.isEmpty()) {
            Portfolio.IssuerWeight top = issuers.getFirst();
            out.add(new Check("Concentration", "Largest single company", top.overLimit() ? Status.WARN : Status.OK,
                    top.issuer() + " is " + pct(top.pctOfNetWorth()) + " of net worth", "at most " + num(l.maxSinglePct()) + " %"));
        }
        // accounts
        if (invested > 0) {
            List<Allocation.Slice> byAccount = Allocation.byAccount(new Portfolio(cur, p.positions(), BigDecimal.ZERO), List.of());
            if (!byAccount.isEmpty()) {
                Allocation.Slice a = byAccount.getFirst();
                out.add(new Check("Accounts", "Largest account (holdings)", a.pct().doubleValue() > l.maxAccountPct() ? Status.WARN : Status.OK,
                        a.name() + " holds " + pct(a.pct()) + " of the invested amount", "at most " + num(l.maxAccountPct()) + " %"));
            }
            // currencies
            List<Allocation.Slice> byCur = Allocation.byCurrency(new Portfolio(cur, p.positions(), BigDecimal.ZERO), List.of());
            double base = byCur.stream().filter(s -> s.name().equals(cur)).mapToDouble(s -> s.pct().doubleValue()).sum();
            out.add(new Check("Currencies", "Invested in " + cur, base < l.minBaseCurrencyPct() ? Status.WARN : Status.OK,
                    num(round(base)) + " % of the invested amount is in " + cur, "at least " + num(l.minBaseCurrencyPct()) + " %"));
            byCur.stream().filter(s -> !s.name().equals(cur)).findFirst().ifPresent(s -> out.add(new Check("Currencies",
                    "Largest foreign currency", s.pct().doubleValue() > l.maxCurrencyPct() ? Status.WARN : Status.OK,
                    s.name() + " is " + pct(s.pct()) + " of the invested amount", "at most " + num(l.maxCurrencyPct()) + " %")));
            // sectors and regions (holdings only)
            Portfolio holdingsOnly = new Portfolio(cur, p.positions(), BigDecimal.ZERO);
            List<Allocation.Slice> sectors = Allocation.bySector(holdingsOnly, profiles);
            sectors.stream().filter(s -> !s.name().equals("Not classified")).findFirst().ifPresent(s -> out.add(new Check("Diversification",
                    "Largest sector", s.pct().doubleValue() > l.maxSectorPct() ? Status.WARN : Status.OK,
                    s.name() + " is " + pct(s.pct()) + " of the invested amount", "at most " + num(l.maxSectorPct()) + " %")));
            List<Allocation.Slice> regions = Allocation.byRegion(holdingsOnly, profiles);
            regions.stream().filter(s -> !s.name().equals("Not classified")).findFirst().ifPresent(s -> out.add(new Check("Diversification",
                    "Largest region", s.pct().doubleValue() > l.maxRegionPct() ? Status.WARN : Status.OK,
                    s.name() + " is " + pct(s.pct()) + " of the invested amount", "at most " + num(l.maxRegionPct()) + " %")));
        }
        // emergency fund
        double cash = p.cashInBase().doubleValue();
        if (l.emergencyFund() <= 0) {
            out.add(new Check("Cash", "Emergency fund", Status.NOT_SET, "No emergency fund amount is set",
                    "set xray.emergencyFund in java\\config\\rules.properties"));
        } else {
            out.add(new Check("Cash", "Emergency fund", cash >= l.emergencyFund() ? Status.OK : Status.WARN,
                    "Cash " + money(cash) + " " + cur + " against an emergency fund of " + money(l.emergencyFund()) + " " + cur,
                    "cash at least the emergency fund"));
        }
        // fees
        double bought = everythingBought == null ? 0 : everythingBought.doubleValue();
        if (bought > 0) {
            double fp = feesPaid.doubleValue() / bought * 100;
            out.add(new Check("Costs", "Fees", fp > l.maxFeePct() ? Status.WARN : Status.OK,
                    money(feesPaid.doubleValue()) + " " + cur + " in fees, " + num(round(fp)) + " % of what was bought",
                    "at most " + num(l.maxFeePct()) + " %"));
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String pct(BigDecimal v) {
        return v == null ? "–" : v.stripTrailingZeros().toPlainString() + " %";
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
