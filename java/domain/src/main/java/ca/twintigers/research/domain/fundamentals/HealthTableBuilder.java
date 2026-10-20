package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.fundamentals.HealthTable.Column;
import ca.twintigers.research.domain.fundamentals.HealthTable.Kind;
import ca.twintigers.research.domain.provenance.Calc;
import ca.twintigers.research.domain.provenance.Cell;
import ca.twintigers.research.domain.provenance.Fact;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the financial health table from filed figures. Port of {@code build_health_table} in
 * research_core/health.py: same columns, rows, formulas and rounding (ratios and growth rates rounded to
 * 4 decimals). Differences from the Python version, all deliberate and listed in the parity report:
 * monetary lookups are restricted to the reporting currency, and total debt comes from
 * {@link DebtResolver}.
 */
public final class HealthTableBuilder {

    public static final String REVENUE = "Revenue";
    public static final String REVENUE_GROWTH = "Revenue growth %";
    public static final String OPERATING_INCOME = "Operating income";
    public static final String OPERATING_MARGIN = "Operating margin %";
    public static final String NET_INCOME = "Net income";
    public static final String OCF = "Operating cash flow";
    public static final String CAPEX = "Capital expenditures";
    public static final String FCF = "Free cash flow";
    public static final String FCF_MARGIN = "FCF margin %";
    public static final String CASH_CONVERSION = "Cash conversion (OCF / net income)";
    public static final String CASH = "Cash and equivalents";
    public static final String TOTAL_DEBT = "Total debt";
    public static final String NET_DEBT = "Net debt (debt − cash)";
    public static final String RECEIVABLES = "Accounts receivable";
    public static final String INVENTORY = "Inventory";
    public static final String DILUTED_SHARES = "Diluted shares (weighted avg)";
    public static final String DILUTION = "Diluted share growth %";

    static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String GROWTH_FORMULA = "(current period / same period one year earlier − 1) × 100";

    private final FactBook book;
    private final Thresholds thresholds;

    public HealthTableBuilder(FactBook book, Thresholds thresholds) {
        this.book = book;
        this.thresholds = thresholds;
    }

    /**
     * @param years number of fiscal years (1 to 5)
     * @param sic   SEC industry code, used to recognise banks (may be null)
     */
    public HealthTable build(int years, Integer sic) {
        List<Period> byRevenue = book.fiscalYears(Tags.REVENUE, years + 1);
        List<Period> fys = byRevenue.isEmpty() ? book.fiscalYears(Tags.OCF, years + 1) : byRevenue;
        if (fys.isEmpty()) {
            throw new IllegalArgumentException("No annual periods found for this company (no 10-K, or no annual report "
                    + "with a full-year income or cash flow statement).");
        }
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < Math.min(years, fys.size()); i++) {
            Period prior = i + 1 < fys.size() ? fys.get(i + 1) : fys.get(i).shiftedBackOneYear("prior FY");
            columns.add(new Column(fys.get(i), Kind.FY, prior));
        }
        java.util.Collections.reverse(columns); // oldest first, like a filing
        Optional<Period[]> ytd = book.latestYtd(Tags.REVENUE, fys.getFirst())
                .or(() -> book.latestYtd(Tags.OCF, fys.getFirst()));
        ytd.ifPresent(p -> {
            columns.add(new Column(p[1], Kind.YTD, p[1].shiftedBackOneYear("prior-prior YTD")));
            columns.add(new Column(p[0], Kind.YTD, p[1]));
        });

        String currency = reportingCurrency(columns);
        DebtResolver debt = new DebtResolver(book, currency, columns.stream().map(c -> c.period().end()).toList());

        Map<String, List<Cell>> rows = new LinkedHashMap<>();
        Function<Function<Column, Cell>, List<Cell>> each = fn -> columns.stream().map(fn).toList();

        rows.put(REVENUE, each.apply(c -> dur(Tags.REVENUE, c.period(), currency)));
        rows.put(REVENUE_GROWTH, each.apply(c -> growth(REVENUE_GROWTH, dur(Tags.REVENUE, c.period(), currency),
                dur(Tags.REVENUE, c.prior(), currency))));
        rows.put(OPERATING_INCOME, each.apply(c -> dur(Tags.OPERATING_INCOME, c.period(), currency)));
        rows.put(OPERATING_MARGIN, each.apply(c -> ratio(OPERATING_MARGIN, dur(Tags.OPERATING_INCOME, c.period(), currency),
                dur(Tags.REVENUE, c.period(), currency), "operating income / revenue × 100", true)));
        rows.put(NET_INCOME, each.apply(c -> dur(Tags.NET_INCOME, c.period(), currency)));
        rows.put(OCF, each.apply(c -> dur(Tags.OCF, c.period(), currency)));
        rows.put(CAPEX, each.apply(c -> dur(Tags.CAPEX, c.period(), currency)));
        rows.put(FCF, each.apply(c -> fcf(c.period(), currency)));
        rows.put(FCF_MARGIN, each.apply(c -> ratio(FCF_MARGIN, fcf(c.period(), currency),
                dur(Tags.REVENUE, c.period(), currency), "free cash flow / revenue × 100", true)));
        rows.put(CASH_CONVERSION, each.apply(c -> ratio("Cash conversion", dur(Tags.OCF, c.period(), currency),
                dur(Tags.NET_INCOME, c.period(), currency), "operating cash flow / net income", false)));
        rows.put(CASH, each.apply(c -> inst(Tags.CASH, c, currency)));
        rows.put(TOTAL_DEBT, each.apply(c -> debt.totalDebt(c.period().end())));
        rows.put(NET_DEBT, each.apply(c -> netDebt(debt.totalDebt(c.period().end()), inst(Tags.CASH, c, currency))));
        rows.put(RECEIVABLES, each.apply(c -> inst(Tags.RECEIVABLES, c, currency)));
        rows.put(INVENTORY, each.apply(c -> inst(Tags.INVENTORY, c, currency)));
        rows.put(DILUTED_SHARES, each.apply(c -> dur(Tags.DILUTED_SHARES, c.period(), "shares")));
        rows.put(DILUTION, each.apply(c -> growth(DILUTION, dur(Tags.DILUTED_SHARES, c.period(), "shares"),
                dur(Tags.DILUTED_SHARES, c.prior(), "shares"))));

        boolean bank = thresholds.isBank(sic);
        var flags = new FundamentalRules(thresholds).evaluate(rows, columns, bank);
        List<String> notes = new ArrayList<>(List.of(
                "Free cash flow is defined here as operating cash flow minus purchases of property and equipment.",
                "Total debt excludes operating lease liabilities. Debt tags used: " + debt.schemeDescription() + ".",
                "Year-to-date columns compare the latest 10-Q (or 6-K) with the same period a year earlier. "
                        + "Balance-sheet rows in YTD columns are as of each period end.",
                "Warning thresholds are analyst-defined assumptions, not standards.",
                "Monetary values are taken in the reporting currency (" + currency + ") only."));
        if (bank) {
            notes.add("SEC classifies this company as a bank/lender (SIC " + sic + "), so the cash conversion test "
                    + "(operating cash flow vs net income) is not applied: deposits and loans swing a bank's operating cash flow.");
        }
        return new HealthTable(book.name(), book.cik(), currency, List.copyOf(columns), rows, flags, notes,
                debt.schemeDescription(), bank);
    }

    /** Unit of the newest revenue (else operating cash flow) fact in the columns. */
    private String reportingCurrency(List<Column> columns) {
        for (List<String> tags : List.of(Tags.REVENUE, Tags.OCF)) {
            for (int i = columns.size() - 1; i >= 0; i--) {
                Optional<Fact> f = book.duration(tags, columns.get(i).period(), null);
                if (f.isPresent() && !"shares".equals(f.get().unit())) {
                    return f.get().unit();
                }
            }
        }
        return null;
    }

    private Fact dur(List<String> tags, Period p, String unit) {
        return book.duration(tags, p, unit).orElse(null);
    }

    private Fact inst(List<String> tags, Column c, String unit) {
        return book.instant(tags, c.period().end(), unit).orElse(null);
    }

    static BigDecimal round4(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    static Calc ratio(String name, Cell num, Cell den, String formula, boolean pct) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("numerator", Calc.ref(num));
        inputs.put("denominator", Calc.ref(den));
        BigDecimal n = num == null ? null : num.value();
        BigDecimal d = den == null ? null : den.value();
        if (n == null || d == null || d.signum() == 0) {
            return Calc.missing(name, formula, inputs, n == null || d == null ? "Missing input." : "Denominator is zero.");
        }
        BigDecimal v = n.divide(d, MC);
        if (pct) {
            v = v.multiply(HUNDRED, MC);
        }
        return new Calc(name, round4(v), formula, inputs, null);
    }

    static Calc growth(String name, Cell cur, Cell prev) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("current", Calc.ref(cur));
        inputs.put("prior", Calc.ref(prev));
        BigDecimal c = cur == null ? null : cur.value();
        BigDecimal p = prev == null ? null : prev.value();
        if (c == null || p == null || p.signum() <= 0) {
            return Calc.missing(name, GROWTH_FORMULA, inputs, "Prior value missing, zero or negative.");
        }
        BigDecimal v = c.divide(p, MC).subtract(BigDecimal.ONE).multiply(HUNDRED, MC);
        return new Calc(name, round4(v), GROWTH_FORMULA, inputs, null);
    }

    private Calc fcf(Period p, String unit) {
        Fact ocf = dur(Tags.OCF, p, unit);
        Fact capex = dur(Tags.CAPEX, p, unit);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("ocf", Calc.ref(ocf));
        inputs.put("capex", Calc.ref(capex));
        String formula = "operating cash flow − capital expenditures";
        if (ocf == null || capex == null) {
            return Calc.missing(FCF, formula, inputs, "Missing input.");
        }
        return new Calc(FCF, ocf.value().subtract(capex.value()), formula, inputs, null);
    }

    private static Calc netDebt(Calc debt, Fact cash) {
        if (debt.value() == null || cash == null) {
            return Calc.missing("Net debt", "total debt − cash", Map.of(), "Missing input.");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("total_debt", debt.value());
        inputs.put("cash", cash.id());
        return new Calc("Net debt", debt.value().subtract(cash.value()), "total debt − cash", inputs, null);
    }
}
