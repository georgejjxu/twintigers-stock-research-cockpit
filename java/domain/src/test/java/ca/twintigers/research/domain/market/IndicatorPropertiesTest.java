package ca.twintigers.research.domain.market;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Properties that must hold for any price series (jqwik generates hundreds of random ones). */
class IndicatorPropertiesTest {

    /** Random walks with whole-number prices (so mirrored arithmetic is exact) and random volumes. */
    @Provide
    Arbitrary<List<Bar>> series() {
        return Arbitraries.integers().between(-4, 4).list().ofMinSize(60).ofMaxSize(160).flatMap(steps ->
                Arbitraries.integers().between(0, 4).list().ofSize(steps.size()).flatMap(wicks ->
                        Arbitraries.longs().between(100_000, 5_000_000).list().ofSize(steps.size()).map(vols -> {
                            List<Bar> bars = new ArrayList<>();
                            double c = 100;
                            LocalDate d = LocalDate.of(2025, 1, 1);
                            for (int i = 0; i < steps.size(); i++) {
                                double o = c;
                                c = Math.max(10, c + steps.get(i));
                                int w = wicks.get(i);
                                bars.add(new Bar(d.plusDays(i), o, Math.max(o, c) + w, Math.min(o, c) - (4 - w) / 2, c, vols.get(i)));
                            }
                            return bars;
                        })));
    }

    @Property(tries = 300)
    boolean rsiStaysBetween0And100(@ForAll("series") List<Bar> bars) {
        double[] rsi = Indicators.rsi(bars.stream().mapToDouble(Bar::close).toArray(), 14);
        for (double v : rsi) {
            if (!Double.isNaN(v) && (v < 0 || v > 100)) {
                return false;
            }
        }
        return true;
    }

    @Property(tries = 300)
    boolean atrIsNonNegativeAndCloseLocationInUnitRange(@ForAll("series") List<Bar> bars) {
        for (double v : Indicators.atr(bars, 14)) {
            if (!Double.isNaN(v) && v < 0) {
                return false;
            }
        }
        return bars.stream().allMatch(b -> b.closeLocation() >= 0 && b.closeLocation() <= 1);
    }

    private static List<Bar> mirror(List<Bar> bars) {
        return bars.stream().map(b -> new Bar(b.date(), 400 - b.open(), 400 - b.low(), 400 - b.high(), 400 - b.close(), b.volume())).toList();
    }

    private static List<LocalDate> fired(List<Bar> bars, String ruleId) {
        MarketContext c = new MarketContext("X", bars, PriceRuleConfig.defaults());
        PriceRule rule = PriceRules.all(c.cfg).stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
        return new RuleEngine(List.of(rule)).scan(c, 0).stream().map(Signal::date).toList();
    }

    /** A buyer-controlled jump is exactly a seller-controlled drop on the price series turned upside down. */
    @Property(tries = 300)
    void buyerJumpMirrorsSellerDrop(@ForAll("series") List<Bar> bars) {
        assertThat(fired(bars, "PA01_buyer_controlled_jump")).isEqualTo(fired(mirror(bars), "PA02_seller_controlled_drop"));
        assertThat(fired(bars, "PA03_gap_up_hold")).isEqualTo(fired(mirror(bars), "PA04_gap_down_no_fill"));
    }

    /** Rules see no future data: the signals up to day k do not change when later days are appended. */
    @Property(tries = 100)
    void noLookAhead(@ForAll("series") List<Bar> bars) {
        int k = bars.size() - 10;
        List<Bar> prefix = bars.subList(0, k);
        MarketContext full = new MarketContext("X", bars, PriceRuleConfig.defaults());
        MarketContext cut = new MarketContext("X", prefix, PriceRuleConfig.defaults());
        RuleEngine e = new RuleEngine(PriceRules.all(full.cfg));
        List<String> a = e.scan(full, 0).stream().filter(s -> s.date().isBefore(bars.get(k).date()))
                .map(s -> s.date() + s.ruleId()).toList();
        List<String> b = e.scan(cut, 0).stream().map(s -> s.date() + s.ruleId()).toList();
        assertThat(a).isEqualTo(b);
    }
}
