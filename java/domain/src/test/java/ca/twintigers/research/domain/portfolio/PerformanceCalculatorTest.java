package ca.twintigers.research.domain.portfolio;

import ca.twintigers.research.domain.market.CorporateAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Returns on hand-checkable examples: one buy, a later buy, a sale, a split, a dividend, a currency move. */
class PerformanceCalculatorTest {

    private static final LocalDate D0 = LocalDate.of(2026, 3, 2); // a Monday

    /** Closes per symbol and USD→CAD rates per day; days are D0 + n. */
    static final class Prices implements PerformanceCalculator.Prices {
        final Map<String, TreeMap<LocalDate, Double>> closes = new HashMap<>();
        final TreeMap<LocalDate, Double> usd = new TreeMap<>();

        Prices close(String symbol, int day, double close) {
            closes.computeIfAbsent(symbol, k -> new TreeMap<>()).put(D0.plusDays(day), close);
            return this;
        }

        @Override
        public double close(String symbol, LocalDate day) {
            var e = closes.getOrDefault(symbol, new TreeMap<>()).floorEntry(day);
            return e == null ? Double.NaN : e.getValue();
        }

        @Override
        public double fx(String currency, LocalDate day) {
            if (currency.equals("CAD")) {
                return 1;
            }
            var e = usd.floorEntry(day);
            return e == null ? Double.NaN : e.getValue();
        }
    }

    static Activity act(String type, int day, String symbol, String qty, String price, String fee, String currency) {
        return new Activity(null, D0.plusDays(day), type, "RRSP", symbol, new BigDecimal(qty), new BigDecimal(price), new BigDecimal(fee),
                currency, null);
    }

    static List<LocalDate> days(int n) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            out.add(D0.plusDays(i));
        }
        return out;
    }

    static PerformanceCalculator.Report run(String range, List<Activity> acts, Prices p, int lastDay) {
        return PerformanceCalculator.compute(range, acts, Map.of(), p, days(lastDay), D0.plusDays(lastDay));
    }

    @Test
    void oneBuyIsValueOverCostIncludingTheFee() {
        Prices p = new Prices().close("ENB.TO", 0, 50).close("ENB.TO", 1, 55);
        var r = run("max", List.of(act("BUY", 0, "ENB.TO", "100", "50", "10", "CAD")), p, 1);
        // cost 5,010; value 5,500
        assertThat(r.valueEnd()).isEqualByComparingTo("5500.00");
        assertThat(r.bought()).isEqualByComparingTo("5010.00");
        assertThat(r.gain()).isEqualByComparingTo("490.00");
        assertThat(r.costBasis()).isEqualByComparingTo("5010.00");
        assertThat(r.priceReturnPct()).isEqualByComparingTo("9.7804"); // 5500/5010 - 1
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void aLaterBuyDoesNotChangeTheTimeWeightedReturn() {
        // +10 % on day 1, then flat; the second, bigger buy on day 2 adds money but no return
        Prices p = new Prices().close("X", 0, 100).close("X", 1, 110).close("X", 2, 110).close("X", 3, 110);
        var r = run("max", List.of(act("BUY", 0, "X", "10", "100", "0", "CAD"), act("BUY", 2, "X", "100", "110", "0", "CAD")), p, 3);
        assertThat(r.priceReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(r.gain()).isEqualByComparingTo("100.00");
        assertThat(r.valueEnd()).isEqualByComparingTo("12100.00");
    }

    @Test
    void aSaleEndsTheExposureAndKeepsTheGain() {
        Prices p = new Prices().close("X", 0, 100).close("X", 1, 120).close("X", 2, 90);
        var r = run("max", List.of(act("BUY", 0, "X", "10", "100", "0", "CAD"), act("SELL", 1, "X", "10", "120", "0", "CAD")), p, 2);
        assertThat(r.valueEnd()).isEqualByComparingTo("0.00");
        assertThat(r.sold()).isEqualByComparingTo("1200.00");
        assertThat(r.gain()).isEqualByComparingTo("200.00");
        assertThat(r.priceReturnPct()).isEqualByComparingTo("20.0000"); // the fall on day 2 is not ours
        assertThat(r.costBasis()).isEqualByComparingTo("0.00");
    }

    @Test
    void aSplitAfterTheBuyKeepsTheValue() {
        // bought 10 at 100 before a 2-for-1 split on day 1; the source's closes are split-adjusted (50 throughout)
        Prices p = new Prices().close("X", 0, 50).close("X", 1, 50).close("X", 2, 50);
        Map<String, List<CorporateAction>> splits = Map.of("X", List.of(CorporateAction.split("X", D0.plusDays(1), 2, 1)));
        var r = PerformanceCalculator.compute("max", List.of(act("BUY", 0, "X", "10", "100", "0", "CAD")), splits, p, days(2), D0.plusDays(2));
        assertThat(r.valueEnd()).isEqualByComparingTo("1000.00");
        assertThat(r.priceReturnPct()).isEqualByComparingTo("0.0000");
        assertThat(PerformanceCalculator.splitFactor("X", D0, splits.get("X"))).isEqualTo(2.0);
        assertThat(PerformanceCalculator.splitFactor("X", D0.plusDays(1), splits.get("X"))).isEqualTo(1.0);
    }

    @Test
    void dividendsCountInTheTotalReturnOnly() {
        Prices p = new Prices().close("X", 0, 100).close("X", 1, 100).close("X", 2, 100);
        var r = run("max", List.of(act("BUY", 0, "X", "10", "100", "0", "CAD"), act("DIVIDEND", 2, "X", "10", "2", "0", "CAD")), p, 2);
        assertThat(r.priceReturnPct()).isEqualByComparingTo("0.0000");
        assertThat(r.totalReturnPct()).isEqualByComparingTo("2.0000");
        assertThat(r.dividends()).isEqualByComparingTo("20.00");
    }

    @Test
    void currencyMovesArePartOfTheReturn() {
        Prices p = new Prices().close("IBM", 0, 200).close("IBM", 1, 200);
        p.usd.put(D0, 1.30);
        p.usd.put(D0.plusDays(1), 1.40);
        var r = run("max", List.of(act("BUY", 0, "IBM", "10", "200", "0", "USD")), p, 1);
        assertThat(r.bought()).isEqualByComparingTo("2600.00");
        assertThat(r.valueEnd()).isEqualByComparingTo("2800.00");
        assertThat(r.priceReturnPct()).isEqualByComparingTo("7.6923");
    }

    @Test
    void foreignCashCountsThroughItsExchangeRateAndBaseCashDoesNot() {
        // 1,000 CAD of shares, flat; 1,000 USD cash from day 0 while USD/CAD goes 1.30 -> 1.43; 5,000 CAD cash ignored
        Prices p = new Prices().close("X", 0, 100).close("X", 1, 100);
        p.usd.put(D0, 1.30);
        p.usd.put(D0.plusDays(1), 1.43);
        List<CashBalance> cash = List.of(new CashBalance("Cash USD", "USD", D0, new BigDecimal("1000")),
                new CashBalance("Cash CAD", "CAD", D0, new BigDecimal("5000")));
        var r = PerformanceCalculator.compute("max", List.of(act("BUY", 0, "X", "10", "100", "0", "CAD")), Map.of(), cash, "CAD", p,
                days(1), D0.plusDays(1));
        assertThat(r.bought()).isEqualByComparingTo("2300.00");    // shares 1,000 + USD cash 1,300
        assertThat(r.valueEnd()).isEqualByComparingTo("2430.00");  // 1,000 + 1,430
        assertThat(r.gain()).isEqualByComparingTo("130.00");
        assertThat(r.priceReturnPct()).isEqualByComparingTo("5.6522"); // 2430 / 2300 - 1
        assertThat(r.costBasis()).isEqualByComparingTo("2300.00");
    }

    @Test
    void rangesStartAfterTheirBaseDay() {
        // bought on day 0 at 100; 110 on day 2, 121 on day 4: "1d" is day 3 -> day 4, a year range only its own days
        Prices p = new Prices().close("X", 0, 100).close("X", 2, 110).close("X", 3, 110).close("X", 4, 121);
        List<Activity> acts = List.of(act("BUY", 0, "X", "1", "100", "0", "CAD"));
        var oneDay = run("1d", acts, p, 4);
        assertThat(oneDay.from()).isEqualTo(D0.plusDays(4));
        assertThat(oneDay.valueStart()).isEqualByComparingTo("110.00");
        assertThat(oneDay.priceReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(run("max", acts, p, 4).priceReturnPct()).isEqualByComparingTo("21.0000");
        assertThat(PerformanceCalculator.period("ytd", D0, D0)[0]).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(PerformanceCalculator.period("2025", D0, D0)).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertThat(PerformanceCalculator.period("wtd", D0.plusDays(2), D0)[0]).isEqualTo(D0);
    }

    @Test
    void noTransactionsGiveNoReturn() {
        var r = run("ytd", List.of(), new Prices(), 1);
        assertThat(r.priceReturnPct()).isNull();
        assertThat(r.notes()).containsExactly("No transactions recorded.");
    }

    @Test
    void dividendsByMonthAndUnrecordedOnes() {
        Prices p = new Prices().close("X", 0, 100);
        p.usd.put(D0, 1.25);
        List<Activity> acts = List.of(act("BUY", 0, "X", "10", "100", "0", "CAD"), act("DIVIDEND", 20, "X", "10", "1", "1", "CAD"),
                act("BUY", 0, "IBM", "4", "200", "0", "USD"), act("DIVIDEND", 25, "IBM", "4", "1.5", "0", "USD"));
        assertThat(Dividends.byMonth(acts, p)).containsExactly(new Dividends.Month(java.time.YearMonth.of(2026, 3), new BigDecimal("16.50"), 2));

        List<CorporateAction> events = List.of(CorporateAction.dividend("X", D0.plusDays(15), 1.0), // recorded on day 20
                CorporateAction.dividend("X", D0.plusDays(120), 1.1),                                  // not recorded
                CorporateAction.dividend("X", D0.minusDays(5), 1.0));                                  // before the buy
        List<Dividends.Missing> missing = Dividends.missing(acts, events, D0.plusDays(200));
        assertThat(missing).hasSize(1);
        assertThat(missing.getFirst().exDate()).isEqualTo(D0.plusDays(120));
        assertThat(missing.getFirst().amount()).isEqualByComparingTo("11.00");
        assertThat(missing.getFirst().account()).isEqualTo("RRSP");
    }
}
