package ca.twintigers.research.domain.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-holding P/L, money put in, sector/region/asset-class allocation, X-ray and FIRE on hand-checkable numbers. */
class GhostfolioScreensTest {

    static final LocalDate D0 = LocalDate.of(2026, 3, 2);

    static Activity act(String type, int day, String symbol, String qty, String price, String fee) {
        return new Activity(null, D0.plusDays(day), type, "RRSP", symbol, new BigDecimal(qty), new BigDecimal(price), new BigDecimal(fee),
                "CAD", null);
    }

    static final PerformanceCalculator.Prices PRICES = new PerformanceCalculator.Prices() {
        @Override
        public double close(String symbol, LocalDate day) {
            return symbol.equals("X") ? 120 : 50;
        }

        @Override
        public double fx(String currency, LocalDate day) {
            return 1;
        }
    };

    @Test
    void holdingProfitAndLossUsesAverageCost() {
        // buy 10 @ 100 + 10 fee, buy 10 @ 110, sell 5 @ 130 - 5 fee, dividend 15 x 1
        List<Activity> acts = List.of(act("BUY", 0, "X", "10", "100", "10"), act("BUY", 1, "X", "10", "110", "0"),
                act("SELL", 2, "X", "5", "130", "5"), act("DIVIDEND", 3, "X", "15", "1", "0"));
        HoldingReturns.Holding h = HoldingReturns.compute(acts, Map.of(), PRICES, D0.plusDays(5)).getFirst();
        // cost 2,110 for 20 shares (105.50 each); 5 sold cost 527.50, proceeds 645 -> realized 117.50
        assertThat(h.quantity()).isEqualByComparingTo("15");
        assertThat(h.avgCost()).isEqualByComparingTo("105.5");
        assertThat(h.costBasis()).isEqualByComparingTo("1582.50");
        assertThat(h.value()).isEqualByComparingTo("1800.00");
        assertThat(h.unrealized()).isEqualByComparingTo("217.50");
        assertThat(h.realized()).isEqualByComparingTo("117.50");
        assertThat(h.dividends()).isEqualByComparingTo("15.00");
        assertThat(h.fees()).isEqualByComparingTo("15.00");
        assertThat(h.totalReturn()).isEqualByComparingTo("350.00");
        assertThat(h.firstBuy()).isEqualTo(D0);
        assertThat(h.transactions()).isEqualTo(4);

        List<Contributions.Month> m = Contributions.byMonth(acts, PRICES);
        assertThat(m).singleElement().satisfies(x -> {
            assertThat(x.bought()).isEqualByComparingTo("2110.00");
            assertThat(x.sold()).isEqualByComparingTo("645.00");
            assertThat(x.net()).isEqualByComparingTo("1465.00");
        });
    }

    static final Portfolio P = new Portfolio("CAD", List.of(
            new Portfolio.Position("ENB.TO", "Enbridge", "CAD", new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("6000"),
                    Map.of("RRSP", new BigDecimal("100"))),
            new Portfolio.Position("IBM", "IBM", "USD", new BigDecimal("10"), new BigDecimal("280"), new BigDecimal("4000"),
                    Map.of("Cash USD", new BigDecimal("10")))),
            new BigDecimal("10000"));
    static final Map<String, InstrumentProfile> PROFILES = Map.of(
            "ENB.TO", new InstrumentProfile("ENB.TO", "EQUITY", "STOCK", Map.of("Energy", 1.0), Map.of("CA", 1.0)),
            "IBM", new InstrumentProfile("IBM", "EQUITY", "STOCK", Map.of("Technology", 1.0), Map.of("US", 1.0)));

    @Test
    void sectorRegionAndAssetClass() {
        assertThat(Allocation.bySector(P, PROFILES)).extracting(Allocation.Slice::name).containsExactly("Cash", "Energy", "Technology");
        assertThat(Allocation.byRegion(P, PROFILES)).extracting(Allocation.Slice::name, s -> s.pct().doubleValue())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Cash", 50.0), org.assertj.core.groups.Tuple.tuple("North America", 50.0));
        assertThat(Allocation.byCountry(P, PROFILES)).extracting(Allocation.Slice::name).contains("Canada", "United States");
        assertThat(Allocation.byAssetClass(P, Map.of())).extracting(Allocation.Slice::name).containsExactlyInAnyOrder("Cash", "Not classified");
        assertThat(Allocation.byAssetClass(P, PROFILES)).extracting(Allocation.Slice::name).containsExactlyInAnyOrder("Cash", "Stocks");
    }

    @Test
    void xrayFindings() {
        XRay.Limits l = XRay.Limits.fromMap(Map.of("xray.emergencyFund", "20000"));
        List<XRay.Check> c = XRay.run(P, List.of(), PROFILES, new BigDecimal("50"), new BigDecimal("10000"), l);
        assertThat(c).extracting(XRay.Check::name).contains("Largest single company", "Largest account (holdings)", "Invested in CAD",
                "Largest foreign currency", "Largest sector", "Largest region", "Emergency fund", "Fees");
        XRay.Check single = c.stream().filter(x -> x.name().equals("Largest single company")).findFirst().orElseThrow();
        assertThat(single.status()).isEqualTo(XRay.Status.WARN); // ENB 30 % of net worth > 10 %
        assertThat(single.finding()).isEqualTo("ENB is 30 % of net worth");
        XRay.Check region = c.stream().filter(x -> x.name().equals("Largest region")).findFirst().orElseThrow();
        assertThat(region.status()).isEqualTo(XRay.Status.WARN); // North America 100 % > 80 %
        XRay.Check fund = c.stream().filter(x -> x.name().equals("Emergency fund")).findFirst().orElseThrow();
        assertThat(fund.status()).isEqualTo(XRay.Status.WARN); // cash 10,000 < 20,000
        XRay.Check fees = c.stream().filter(x -> x.name().equals("Fees")).findFirst().orElseThrow();
        assertThat(fees.status()).isEqualTo(XRay.Status.OK); // 0.5 %
        assertThat(XRay.run(P, List.of(), PROFILES, BigDecimal.ZERO, BigDecimal.ZERO, XRay.Limits.fromMap(Map.of())))
                .filteredOn(x -> x.name().equals("Emergency fund")).singleElement().extracting(XRay.Check::status).isEqualTo(XRay.Status.NOT_SET);
    }

    @Test
    void fireWithdrawalAndProjection() {
        Fire.Result r = Fire.compute(1_000_000, 50_000, 5, 4, 60_000, 10, 2026);
        assertThat(r.withdrawalYear()).isEqualByComparingTo("40000.00");
        assertThat(r.withdrawalMonth()).isEqualByComparingTo("3333.33");
        assertThat(r.target()).isEqualByComparingTo("1500000.00");
        assertThat(r.projection().getFirst().netWorth()).isEqualByComparingTo("1100000.00"); // 1,000,000 x 1.05 + 50,000
        assertThat(r.projection().getFirst().year()).isEqualTo(2027);
        assertThat(r.yearsToTarget()).isEqualTo(5); // 1,697,... after 5 years
        assertThat(Fire.compute(2_000_000, 0, 0, 4, 60_000, 5, 2026).yearsToTarget()).isZero();
    }
}
