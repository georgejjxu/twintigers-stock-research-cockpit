package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.provenance.Calc;
import ca.twintigers.research.domain.provenance.Fact;
import ca.twintigers.research.domain.rules.Flag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Total debt (the Enbridge case) and the new fundamental rules F08–F11, on hand-built facts. */
class DebtAndRulesTest {

    private final Map<String, List<Fact>> facts = new HashMap<>();
    private int accession = 1;

    private void instant(String concept, String unit, String end, long millions) {
        facts.computeIfAbsent(FactBook.qualify(concept), k -> new ArrayList<>()).add(new Fact(FactBook.qualify(concept),
                BigDecimal.valueOf(millions * 1_000_000L), unit, LocalDate.parse(end), null, "10-K",
                "0000000000-26-" + String.format("%06d", accession++), LocalDate.parse(end).plusDays(40), 1L));
    }

    private void annual(String concept, int year, long millions) {
        facts.computeIfAbsent(FactBook.qualify(concept), k -> new ArrayList<>()).add(new Fact(FactBook.qualify(concept),
                BigDecimal.valueOf(millions * 1_000_000L), "CAD", LocalDate.of(year, 12, 31), LocalDate.of(year, 1, 1), "10-K",
                "0000000000-26-" + String.format("%06d", accession++), LocalDate.of(year + 1, 2, 10), 1L));
    }

    private FactBook book() {
        return new FactBook(new CompanyFacts(1L, "Test Co", facts));
    }

    @Test
    void footnoteFragmentAndForeignCurrencyAreNotTotalDebt() {
        // Enbridge 2023: a C$460M footnote tag + a EUR value, while the balance sheet tags debt with finance leases.
        instant("LongTermDebtNoncurrent", "CAD", "2023-12-31", 460);
        instant("LongTermDebtNoncurrent", "EUR", "2023-12-31", 316);
        instant("LongTermDebtCurrent", "CAD", "2023-12-31", 61);
        for (String[] d : new String[][] {{"2023-12-31", "74715", "6084", "400"}, {"2025-06-30", "96981", "3158", "914"}}) {
            instant("LongTermDebtAndCapitalLeaseObligations", "CAD", d[0], Long.parseLong(d[1]));
            instant("LongTermDebtAndCapitalLeaseObligationsCurrent", "CAD", d[0], Long.parseLong(d[2]));
            instant("ShortTermBorrowings", "CAD", d[0], Long.parseLong(d[3]));
        }
        instant("LongTermDebt", "CAD", "2023-12-31", 80438);

        DebtResolver r = new DebtResolver(book(), "CAD", List.of(LocalDate.parse("2023-12-31"), LocalDate.parse("2025-06-30")));
        assertThat(r.schemeDescription()).startsWith("C:");
        Calc d2023 = r.totalDebt(LocalDate.parse("2023-12-31"));
        assertThat(d2023.value()).isEqualByComparingTo(BigDecimal.valueOf(81_199_000_000L)); // 74,715 + 6,084 + 400
        assertThat(d2023.formula()).contains("long_term_debt_and_finance_leases").contains("short_term_borrowings");
        assertThat(r.totalDebt(LocalDate.parse("2025-06-30")).value()).isEqualByComparingTo(BigDecimal.valueOf(101_053_000_000L));
    }

    @Test
    void shortTermBorrowingsAloneAreNeverTotalDebt() {
        instant("ShortTermBorrowings", "CAD", "2024-12-31", 529);
        Calc d = new DebtResolver(book(), "CAD", List.of(LocalDate.parse("2024-12-31"))).totalDebt(LocalDate.parse("2024-12-31"));
        assertThat(d.value()).isNull();
        assertThat(d.note()).contains("not reported alone");
    }

    private HealthTable fcfCompany(long[] ocf, long[] capex, long[] netDebtParts, Integer sic) {
        for (int k = 0; k < ocf.length; k++) {
            int year = 2022 + k;
            annual("Revenues", year, 10_000);
            annual("NetIncomeLoss", year, 1_000);
            annual("NetCashProvidedByUsedInOperatingActivities", year, ocf[k]);
            annual("PaymentsToAcquirePropertyPlantAndEquipment", year, capex[k]);
            instant("LongTermDebt", "CAD", year + "-12-31", netDebtParts[k]);
            instant("CashAndCashEquivalentsAtCarryingValue", "CAD", year + "-12-31", 100);
        }
        return new HealthTableBuilder(book(), Thresholds.defaults()).build(3, sic);
    }

    @Test
    void enbridgeLikeFcfDropFiresF08AndF10() {
        HealthTable t = fcfCompany(new long[] {14_000, 14_201, 12_600, 12_270}, new long[] {4_000, 4_654, 6_711, 8_973},
                new long[] {70_000, 80_000, 82_000, 84_000}, null);
        assertThat(t.flags()).extracting(Flag::ruleId).contains("F08_fcf_drop", "F10_fcf_declining_years")
                .doesNotContain("F11_net_debt_rise");
        Flag f08 = t.flags().stream().filter(f -> f.ruleId().equals("F08_fcf_drop")).findFirst().orElseThrow();
        assertThat(f08.text()).isEqualTo("Free cash flow fell 44% in the latest fiscal year (5,889 to 3,297 millions).");
        assertThat(f08.evidence()).containsKeys("fcf_prior", "fcf_latest", "drop_pct", "threshold_pct");
    }

    @Test
    void smallFcfDeclinesAndRisingNetDebt() {
        // IBM-like: FCF slips ~2% a year (not counted), net debt +16% (F11).
        HealthTable t = fcfCompany(new long[] {14_000, 13_931, 13_445, 13_193}, new long[] {1_000, 1_245, 1_048, 1_091},
                new long[] {40_000, 41_126, 41_126, 47_773}, null);
        assertThat(t.flags()).extracting(Flag::ruleId).contains("F11_net_debt_rise").doesNotContain("F10_fcf_declining_years", "F08_fcf_drop");
    }

    @Test
    void cashConversionRuleIsNotAppliedToBanks() {
        long[] ocf = {700, 700, 700, 700};
        long[] capex = {0, 0, 0, 0};
        long[] debt = {10, 10, 10, 10};
        assertThat(fcfCompany(ocf, capex, debt, null).flags()).extracting(Flag::ruleId).contains("F05_low_cash_conversion");
        facts.clear();
        HealthTable bank = fcfCompany(ocf, capex, debt, 6022);
        assertThat(bank.bank()).isTrue();
        assertThat(bank.flags()).extracting(Flag::ruleId).doesNotContain("F05_low_cash_conversion");
        assertThat(bank.notes()).anyMatch(n -> n.contains("bank/lender (SIC 6022)"));
    }

    @Test
    void thresholdsComeFromConfiguration() {
        Thresholds t = Thresholds.fromMap(Map.of("fundamental.fcfDropPct", "50", "fundamental.bankSicMax", "6299"));
        assertThat(t.fcfDropPct()).isEqualTo(50.0);
        assertThat(t.isBank(6211)).isTrue();
        assertThat(t.dilutionMaxPct()).isEqualTo(2.0);
    }
}
