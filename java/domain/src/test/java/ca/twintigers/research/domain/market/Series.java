package ca.twintigers.research.domain.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Builds synthetic daily bar series for rule tests. */
final class Series {

    private final List<Bar> bars = new ArrayList<>();
    private LocalDate date = LocalDate.of(2026, 1, 1);

    Series bar(double open, double high, double low, double close, long volume) {
        bars.add(new Bar(date, open, high, low, close, volume));
        date = date.plusDays(1);
        return this;
    }

    double lastClose() {
        return bars.getLast().close();
    }

    /** n quiet days drifting by step per day: range 1.0 around the body, volume 1M. */
    Series drift(int n, double step) {
        double c = bars.isEmpty() ? 100 : lastClose();
        for (int k = 0; k < n; k++) {
            double o = c;
            c = c + step;
            bar(o, Math.max(o, c) + 0.4, Math.min(o, c) - 0.4, c, 1_000_000);
        }
        return this;
    }

    List<Bar> bars() {
        return List.copyOf(bars);
    }

    MarketContext ctx() {
        return new MarketContext("TEST", bars, PriceRuleConfig.defaults());
    }

    /** Rule ids that fire on the last bar. */
    List<String> firedOnLast() {
        MarketContext c = ctx();
        return PriceRules.all(c.cfg).stream().filter(r -> r.evaluate(c, c.size() - 1).isPresent()).map(PriceRule::id).toList();
    }
}
