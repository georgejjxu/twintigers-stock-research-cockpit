package ca.twintigers.research.domain.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Allocation by holding, currency and account adds up to net worth. */
class AllocationTest {

    static final Portfolio P = new Portfolio("CAD", List.of(
            new Portfolio.Position("ENB.TO", "Enbridge", "CAD", new BigDecimal("300"), new BigDecimal("10"), new BigDecimal("3000"),
                    Map.of("RRSP", new BigDecimal("200"), "Cash CAD", new BigDecimal("100"))),
            new Portfolio.Position("IBM", "IBM", "USD", new BigDecimal("5"), new BigDecimal("200"), new BigDecimal("1400"),
                    Map.of("Cash USD", new BigDecimal("5")))),
            new BigDecimal("5600"));
    static final List<Account> ACCOUNTS = List.of(new Account("Cash CAD", "CAD", new BigDecimal("4200"), new BigDecimal("4200")),
            new Account("Cash USD", "USD", new BigDecimal("1000"), new BigDecimal("1400")), new Account("RRSP", "CAD", BigDecimal.ZERO, BigDecimal.ZERO));

    static Map<String, String> asMap(List<Allocation.Slice> s) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        s.forEach(x -> m.put(x.name(), x.valueInBase().toPlainString() + " / " + x.pct().toPlainString()));
        return m;
    }

    @Test
    void slicesAddUpToNetWorth() {
        // net worth 10,000
        assertThat(asMap(Allocation.byHolding(P))).containsExactly(Map.entry("Cash", "5600.00 / 56.00"),
                Map.entry("ENB.TO", "3000.00 / 30.00"), Map.entry("IBM", "1400.00 / 14.00"));
        assertThat(asMap(Allocation.byCurrency(P, ACCOUNTS))).containsExactly(Map.entry("CAD", "7200.00 / 72.00"),
                Map.entry("USD", "2800.00 / 28.00"));
        assertThat(asMap(Allocation.byAccount(P, ACCOUNTS))).containsExactly(Map.entry("Cash CAD", "5200.00 / 52.00"),
                Map.entry("Cash USD", "2800.00 / 28.00"), Map.entry("RRSP", "2000.00 / 20.00"));
    }
}
