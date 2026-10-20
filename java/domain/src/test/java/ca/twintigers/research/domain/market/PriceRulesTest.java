package ca.twintigers.research.domain.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Each rule fires on a textbook example, with its evidence, and stays quiet without its context. */
class PriceRulesTest {

    @Test
    void indicatorsHaveTextbookValues() {
        double[] x = {1, 2, 3, 4, 5};
        assertThat(Indicators.sma(x, 3)[4]).isEqualTo(4.0);
        assertThat(Indicators.ema(x, 3)[2]).isEqualTo(2.0); // seed = SMA of first 3
        assertThat(Indicators.ema(x, 3)[3]).isEqualTo(3.0); // 0.5 * 4 + 0.5 * 2
        double[] rising = new double[30];
        for (int i = 0; i < 30; i++) {
            rising[i] = 100 + i;
        }
        assertThat(Indicators.rsi(rising, 14)[29]).isEqualTo(100.0);
        List<Bar> flat = new Series().drift(30, 0).bars();
        assertThat(Indicators.atr(flat, 14)[29]).isCloseTo(0.8, within(1e-9));
        assertThat(Indicators.priorAverageVolume(flat, 20)[25]).isEqualTo(1_000_000.0);
    }

    @Test
    void buyerControlledJump() {
        Series s = new Series().drift(60, 0).bar(100, 102.2, 99.9, 102.1, 2_500_000);
        assertThat(s.firedOnLast()).contains("PA01_buyer_controlled_jump").doesNotContain("PA02_seller_controlled_drop");
        MarketContext c = s.ctx();
        Map<String, Object> ev = PriceRules.all(c.cfg).getFirst().evaluate(c, c.size() - 1).orElseThrow().evidence();
        assertThat(ev).containsEntry("rel_volume", 2.5).containsEntry("close_location", 0.96).containsKey("atr_prev");
    }

    @Test
    void bigUpDayWithoutVolumeIsNotBuyerControlled() {
        Series s = new Series().drift(60, 0).bar(100, 102.2, 99.9, 102.1, 1_100_000);
        assertThat(s.firedOnLast()).doesNotContain("PA01_buyer_controlled_jump");
    }

    @Test
    void sellerControlledDrop() {
        Series s = new Series().drift(60, 0).bar(100, 100.1, 97.8, 97.9, 2_000_000);
        assertThat(s.firedOnLast()).contains("PA02_seller_controlled_drop");
    }

    @Test
    void gapDownThatDoesNotFill() {
        Series s = new Series().drift(60, 0).bar(98, 98.5, 97, 97.2, 1_500_000);
        assertThat(s.firedOnLast()).contains("PA04_gap_down_no_fill");
    }

    @Test
    void gapUpThatHolds() {
        Series s = new Series().drift(60, 0).bar(102, 103, 101.5, 102.8, 1_500_000);
        assertThat(s.firedOnLast()).contains("PA03_gap_up_hold");
    }

    @Test
    void sustainedDeclineOnVolumeFiresOnceOnTheFirstDay() {
        Series s = new Series().drift(60, 0);
        double c = 100;
        for (int k = 0; k < 7; k++) {
            s.bar(c, c + 0.2, c - 0.9, c - 0.7, 1_600_000);
            c -= 0.7;
        }
        MarketContext ctx = s.ctx();
        List<Signal> found = new RuleEngine(PriceRules.all(ctx.cfg)).scan(ctx, 0).stream()
                .filter(x -> x.ruleId().equals("PA05_sustained_decline_on_volume")).toList();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().date()).isEqualTo(ctx.bar(64).date()); // 5th lower close
        assertThat(found.getFirst().evidence()).containsEntry("days", 5).containsKey("volume_ratio");
    }

    @Test
    void closeBelowSupport() {
        Series s = new Series().drift(45, 0);
        // two swing lows near 95 (support), separated by rallies, then a break below
        double[][] path = {{100, 97}, {97, 95}, {95.6, 98}, {98, 100}, {100, 100}, {100, 100}, {100, 100}, {100, 97},
                {97, 95.1}, {95.7, 98}, {98, 100}, {100, 100}, {100, 100}, {100, 100}, {100, 98}, {98, 96}, {96, 93.5}};
        for (double[] p : path) {
            s.bar(p[0], Math.max(p[0], p[1]) + 0.3, Math.min(p[0], p[1]) - 0.3, p[1], 1_000_000);
        }
        assertThat(s.firedOnLast()).contains("PA06_support_breakdown");
    }

    @Test
    void bearishEngulfingNeedsAnUpTrend() {
        Series up = new Series().drift(60, 0.3);
        double c = up.lastClose();
        up.bar(c, c + 1.2, c - 0.1, c + 1.0, 1_000_000).bar(c + 1.3, c + 1.4, c - 0.6, c - 0.4, 1_500_000);
        assertThat(up.firedOnLast()).contains("K01_bearish_engulfing");

        Series down = new Series().drift(60, -0.3);
        double d = down.lastClose();
        down.bar(d, d + 1.2, d - 0.1, d + 1.0, 1_000_000).bar(d + 1.3, d + 1.4, d - 0.6, d - 0.4, 1_500_000);
        assertThat(down.firedOnLast()).doesNotContain("K01_bearish_engulfing");
    }

    @Test
    void shootingStarAndHangingManAfterUpTrend() {
        Series s = new Series().drift(60, 0.3);
        double c = s.lastClose();
        s.bar(c, c + 2.0, c - 0.05, c + 0.2, 1_000_000); // long upper shadow
        assertThat(s.firedOnLast()).contains("K02_shooting_star");

        Series h = new Series().drift(60, 0.3);
        double d = h.lastClose();
        h.bar(d, d + 0.25, d - 2.0, d + 0.2, 1_000_000); // long lower shadow
        assertThat(h.firedOnLast()).contains("K04_hanging_man").doesNotContain("K07_hammer");
    }

    @Test
    void hammerAfterDownTrend() {
        Series s = new Series().drift(60, -0.3);
        double c = s.lastClose();
        s.bar(c, c + 0.25, c - 2.0, c + 0.2, 1_000_000);
        assertThat(s.firedOnLast()).contains("K07_hammer").doesNotContain("K04_hanging_man");
    }

    @Test
    void eveningAndMorningStar() {
        Series s = new Series().drift(60, 0.3);
        double c = s.lastClose();
        s.bar(c, c + 1.6, c - 0.1, c + 1.5, 1_000_000)          // long up day
         .bar(c + 1.8, c + 2.2, c + 1.6, c + 1.9, 1_000_000)    // small star above
         .bar(c + 1.7, c + 1.8, c + 0.2, c + 0.4, 1_400_000);   // closes below first body's middle
        assertThat(s.firedOnLast()).contains("K03_evening_star");

        Series m = new Series().drift(60, -0.3);
        double d = m.lastClose();
        m.bar(d, d + 0.1, d - 1.6, d - 1.5, 1_000_000)
         .bar(d - 1.8, d - 1.6, d - 2.2, d - 1.9, 1_000_000)
         .bar(d - 1.7, d - 0.2, d - 1.8, d - 0.4, 1_400_000);
        assertThat(m.firedOnLast()).contains("K09_morning_star");
    }

    @Test
    void darkCloudCover() {
        Series s = new Series().drift(60, 0.3);
        double c = s.lastClose();
        s.bar(c, c + 1.6, c - 0.1, c + 1.5, 1_000_000).bar(c + 1.8, c + 1.9, c + 0.4, c + 0.5, 1_300_000);
        assertThat(s.firedOnLast()).contains("K05_dark_cloud_cover").doesNotContain("K01_bearish_engulfing");
    }

    @Test
    void threeBlackCrows() {
        Series s = new Series().drift(60, 0.3);
        double c = s.lastClose();
        s.bar(c + 0.1, c + 0.2, c - 1.3, c - 1.2, 1_200_000)
         .bar(c - 0.9, c - 0.8, c - 2.4, c - 2.3, 1_300_000)
         .bar(c - 2.0, c - 1.9, c - 3.6, c - 3.5, 1_400_000);
        assertThat(s.firedOnLast()).contains("K06_three_black_crows");
    }

    @Test
    void bullishEngulfingAfterDownTrend() {
        Series s = new Series().drift(60, -0.3);
        double c = s.lastClose();
        s.bar(c, c + 0.1, c - 1.2, c - 1.0, 1_000_000).bar(c - 1.3, c + 0.6, c - 1.4, c + 0.4, 1_500_000);
        assertThat(s.firedOnLast()).contains("K08_bullish_engulfing");
    }

    @Test
    void rsiAndMacdCrossesAreEdgeTriggered() {
        Series s = new Series().drift(60, -0.2).drift(25, 0.6);
        MarketContext c = s.ctx();
        List<Signal> all = new RuleEngine(PriceRules.all(c.cfg)).scan(c, 0);
        assertThat(all).filteredOn(x -> x.ruleId().equals("M04_macd_bullish_cross")).hasSize(1);
        assertThat(all).filteredOn(x -> x.ruleId().equals("M01_rsi_overbought")).hasSize(1);
    }

    @Test
    void configurationOverridesDefaults() {
        PriceRuleConfig c = PriceRuleConfig.fromMap(Map.of("price.jumpAtrMultiple", "3.0", "price.rsiOverbought", "80"));
        assertThat(c.jumpAtrMultiple()).isEqualTo(3.0);
        assertThat(c.rsiOverbought()).isEqualTo(80.0);
        assertThat(c.atrPeriod()).isEqualTo(14);
        Series s = new Series().drift(60, 0).bar(100, 102.2, 99.9, 102.1, 2_500_000);
        MarketContext ctx = new MarketContext("T", s.bars(), c);
        assertThat(PriceRules.all(c).getFirst().evaluate(ctx, ctx.size() - 1)).isEmpty();
    }
}
