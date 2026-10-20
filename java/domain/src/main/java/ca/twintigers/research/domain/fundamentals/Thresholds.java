package ca.twintigers.research.domain.fundamentals;

/**
 * Analyst-defined warning thresholds for the fundamental rules. Assumptions, not standards; every one
 * can be overridden in the rules configuration file.
 *
 * @param operatingMarginDropPts flag when operating margin falls more than this many points in a year
 * @param cashConversionMin      flag when operating cash flow / net income is below this (not for banks)
 * @param dilutionMaxPct         flag when diluted share count grows more than this % in a year
 * @param debtRisePct            flag when total debt rises more than this % while cash falls
 * @param fcfDropPct             flag when free cash flow falls more than this % year over year
 * @param fcfDeclineYears        flag when free cash flow has fallen this many years in a row
 * @param fcfDeclineMinPct       ... where a year only counts as a fall if it is at least this % of the prior year's absolute value
 * @param netDebtRisePct         flag when net debt rises more than this % in a year
 * @param bankSicMin             SIC codes from bankSicMin to bankSicMax are treated as banks / lenders
 * @param bankSicMax             (cash-conversion rule not applicable: deposits and loans swing operating cash flow)
 */
public record Thresholds(double operatingMarginDropPts, double cashConversionMin, double dilutionMaxPct,
                         double debtRisePct, double fcfDropPct, int fcfDeclineYears, double fcfDeclineMinPct, double netDebtRisePct,
                         int bankSicMin, int bankSicMax) {

    public static Thresholds defaults() {
        return new Thresholds(3.0, 0.8, 2.0, 20.0, 30.0, 2, 5.0, 10.0, 6000, 6199);
    }

    /** Defaults overridden by {@code fundamental.<field>} entries of the map; unknown keys are ignored. */
    public static Thresholds fromMap(java.util.Map<String, String> m) {
        Thresholds d = defaults();
        java.util.function.BiFunction<String, Double, Double> n = (k, def) -> {
            String v = m.get("fundamental." + k);
            return v == null ? def : Double.parseDouble(v.trim());
        };
        return new Thresholds(n.apply("operatingMarginDropPts", d.operatingMarginDropPts),
                n.apply("cashConversionMin", d.cashConversionMin), n.apply("dilutionMaxPct", d.dilutionMaxPct),
                n.apply("debtRisePct", d.debtRisePct), n.apply("fcfDropPct", d.fcfDropPct),
                n.apply("fcfDeclineYears", (double) d.fcfDeclineYears).intValue(), n.apply("fcfDeclineMinPct", d.fcfDeclineMinPct),
                n.apply("netDebtRisePct", d.netDebtRisePct), n.apply("bankSicMin", (double) d.bankSicMin).intValue(),
                n.apply("bankSicMax", (double) d.bankSicMax).intValue());
    }

    public boolean isBank(Integer sic) {
        return sic != null && sic >= bankSicMin && sic <= bankSicMax;
    }
}
