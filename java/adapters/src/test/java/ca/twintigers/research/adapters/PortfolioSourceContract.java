package ca.twintigers.research.adapters;

import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.PortfolioSource.Capability;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behaviour every {@link PortfolioSource} must have. Each implementation runs this same suite (subclasses
 * provide the source, set up with the same logical portfolio):
 * <ul>
 *   <li>Cash CAD: 1,100 ENB.TO; RRSP: 980 ENB.TO; Cash USD: 800 ENB (NYSE) and US$70,000 cash; Cash CAD: C$400,000 cash</li>
 *   <li>ENB.TO last price 68.04 CAD; one dividend recorded (when the source has activities)</li>
 * </ul>
 * A new source (e.g. a broker API) is done when a subclass of this passes.
 */
abstract class PortfolioSourceContract {

    abstract PortfolioSource source() throws Exception;

    @Test
    void identifiesItselfAndItsCapabilities() throws Exception {
        PortfolioSource s = source();
        assertThat(s.name()).isNotBlank();
        assertThat(s.capabilities()).isNotNull();
    }

    @Test
    void positionsHaveSymbolsQuantitiesValuesAndTheAccountSplit() throws Exception {
        Portfolio p = source().portfolio();
        assertThat(p.baseCurrency()).isEqualTo("CAD");
        assertThat(p.positions()).extracting(Portfolio.Position::symbol).containsExactly("ENB.TO", "ENB");
        Portfolio.Position enb = p.positions().getFirst();
        assertThat(enb.quantity()).isEqualByComparingTo("2080");
        assertThat(enb.marketPrice()).isEqualByComparingTo("68.04");
        assertThat(enb.valueInBase()).isEqualByComparingTo("141523.20"); // 2,080 × 68.04, the same from every source
        assertThat(enb.sharesByAccount()).containsOnlyKeys("Cash CAD", "RRSP");
        assertThat(enb.sharesByAccount().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(enb.quantity());
        assertThat(p.positions()).allSatisfy(x -> {
            assertThat(x.valueInBase()).isPositive();
            assertThat(x.currency()).isIn("CAD", "USD");
        });
        assertThat(p.positions().get(0).valueInBase()).isGreaterThanOrEqualTo(p.positions().get(1).valueInBase()); // largest first
        assertThat(p.cashInBase()).isGreaterThan(new BigDecimal("400000"));
    }

    @Test
    void issuerWeightsCombineListings() throws Exception {
        Portfolio.IssuerWeight enb = source().portfolio().issuerWeights(20).getFirst();
        assertThat(enb.issuer()).isEqualTo("ENB");
        assertThat(enb.symbols()).containsExactlyInAnyOrder("ENB.TO", "ENB");
    }

    @Test
    void accountsWhenSupported() throws Exception {
        PortfolioSource s = source();
        List<Account> accounts = s.accounts();
        if (!s.capabilities().contains(Capability.ACCOUNTS)) {
            assertThat(accounts).isEmpty();
            return;
        }
        assertThat(accounts).extracting(Account::name).contains("Cash CAD", "Cash USD", "RRSP");
        Account usd = accounts.stream().filter(a -> a.name().equals("Cash USD")).findFirst().orElseThrow();
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.cashBalance()).isEqualByComparingTo("70000");
    }

    @Test
    void activitiesAndDividendsWhenSupported() throws Exception {
        PortfolioSource s = source();
        List<Activity> acts = s.activities();
        if (!s.capabilities().contains(Capability.ACTIVITIES)) {
            assertThat(acts).isEmpty();
            assertThat(s.dividends()).isEmpty();
            return;
        }
        assertThat(acts).hasSize(4);
        assertThat(acts).isSortedAccordingTo((a, b) -> b.date().compareTo(a.date())); // newest first
        assertThat(s.dividends()).singleElement().satisfies(d -> {
            assertThat(d.symbol()).isEqualTo("ENB.TO");
            assertThat(d.account()).isEqualTo("RRSP");
        });
    }

    @Test
    void performanceOnlyWhenDeclared() throws Exception {
        PortfolioSource s = source();
        if (s.capabilities().contains(Capability.PERFORMANCE)) {
            assertThat(s.performance("ytd")).hasValueSatisfying(p -> {
                assertThat(p.range()).isEqualTo("ytd");
                assertThat(p.method()).isNotBlank();
            });
        } else {
            assertThat(s.performance("ytd")).isEmpty();
        }
    }
}
