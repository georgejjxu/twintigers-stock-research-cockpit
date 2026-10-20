package ca.twintigers.research.domain.alerts;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.MarketCalendar;
import ca.twintigers.research.domain.market.MarketCalendar.Exchange;
import ca.twintigers.research.domain.rules.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AlertDomainTest {

    static final Instant T0 = Instant.parse("2026-10-19T14:00:00Z");
    static final Duration COOLDOWN = Duration.ofHours(24);

    static Alert alert(Alert.Kind k, Double level, Double pct, Double ref, Double atr) {
        return new Alert(1, k, "ENB.TO", level, pct, ref, atr, LocalDate.of(2026, 1, 1), null, T0, null, true, AlertState.armed(T0));
    }

    @Test
    void stateMachineNotifiesOncePerCrossing() {
        AlertState s = AlertState.armed(T0);
        var t1 = s.evaluate(true, T0, COOLDOWN);
        assertThat(t1.shouldNotify()).isTrue();
        assertThat(t1.next().status()).isEqualTo(AlertState.Status.TRIGGERED);
        // next run, condition still true: no second notification
        var t2 = t1.next().evaluate(true, T0.plus(Duration.ofMinutes(30)), COOLDOWN);
        assertThat(t2.shouldNotify()).isFalse();
        // price falls back during the cooldown: still no re-arm
        var t3 = t2.next().evaluate(false, T0.plus(Duration.ofHours(2)), COOLDOWN);
        assertThat(t3.next().status()).isEqualTo(AlertState.Status.TRIGGERED);
        assertThat(t3.change()).isEqualTo("in cooldown");
        // after the cooldown, condition false: re-armed, still silent
        var t4 = t3.next().evaluate(false, T0.plus(Duration.ofHours(25)), COOLDOWN);
        assertThat(t4.next().status()).isEqualTo(AlertState.Status.ARMED);
        assertThat(t4.shouldNotify()).isFalse();
        // crosses again: notifies again
        assertThat(t4.next().evaluate(true, T0.plus(Duration.ofHours(26)), COOLDOWN).shouldNotify()).isTrue();
    }

    @Test
    void conditionThatNeverClearsNeverRenotifies() {
        AlertState s = AlertState.armed(T0).evaluate(true, T0, COOLDOWN).next();
        for (int h = 1; h < 24 * 10; h += 7) {
            var t = s.evaluate(true, T0.plus(Duration.ofHours(h)), COOLDOWN);
            assertThat(t.shouldNotify()).isFalse();
            s = t.next();
        }
    }

    @Test
    void thresholdKinds() {
        assertThat(ThresholdCheck.evaluate(alert(Alert.Kind.ABOVE, 75.0, null, null, null), 75.42, "t", List.of()).condition()).isTrue();
        assertThat(ThresholdCheck.evaluate(alert(Alert.Kind.ABOVE, 75.0, null, null, null), 74.99, "t", List.of()).condition()).isFalse();
        var below = ThresholdCheck.evaluate(alert(Alert.Kind.BELOW, 60.0, null, null, null), 59.5, "t", List.of());
        assertThat(below.condition()).isTrue();
        assertThat(below.text()).isEqualTo("ENB.TO at 59.50 is at or below your level 60.00.");
        var down = ThresholdCheck.evaluate(alert(Alert.Kind.MOVE_PCT, null, -8.0, 68.0, null), 62.5, "t", List.of());
        assertThat(down.condition()).isTrue();
        assertThat(down.evidence()).containsEntry("move_pct", -8.09);
        assertThat(ThresholdCheck.evaluate(alert(Alert.Kind.MOVE_PCT, null, 10.0, 68.0, null), 74.0, "t", List.of()).condition()).isFalse();
    }

    @Test
    void trailingStopUsesHighestCloseSinceStartMinusAtr() {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 30; i++) {
            double c = 100 + i; // rises to 129, range 2 → ATR ≈ 2
            bars.add(new Bar(d.plusDays(i), c - 0.5, c + 1, c - 1, c, 1000));
        }
        var r = ThresholdCheck.evaluate(alert(Alert.Kind.TRAILING_ATR, null, null, null, 3.0), 122.0, "t", bars);
        assertThat(r.evidence()).containsEntry("highest_close", 129.0);
        double stop = (double) r.evidence().get("stop");
        assertThat(stop).isLessThan(129.0).isGreaterThan(121.0);
        assertThat(r.condition()).isTrue();
        assertThat(ThresholdCheck.evaluate(alert(Alert.Kind.TRAILING_ATR, null, null, null, 3.0), 128.0, "t", bars).condition()).isFalse();
    }

    @Test
    void subscriptionsMatchScopeRuleAndSeverity() {
        Subscription all = new Subscription(1, "*", Subscription.Source.PRICE, "K05_dark_cloud_cover", Severity.WARNING, "default", T0, null, true);
        assertThat(all.matches("IBM", Subscription.Source.PRICE, "K05_dark_cloud_cover", Severity.WARNING, T0)).isTrue();
        assertThat(all.matches("IBM", Subscription.Source.PRICE, "K01_bearish_engulfing", Severity.WARNING, T0)).isFalse();
        assertThat(all.matches("IBM", Subscription.Source.FUNDAMENTAL, "K05_dark_cloud_cover", Severity.WARNING, T0)).isFalse();
        Subscription enb = new Subscription(2, "ENB", Subscription.Source.PRICE, "PA*", Severity.WATCH, "user", T0, null, true);
        assertThat(enb.matches("ENB.TO", Subscription.Source.PRICE, "PA02_seller_controlled_drop", Severity.WARNING, T0)).isTrue();
        assertThat(enb.matches("ENB", Subscription.Source.PRICE, "PA03_gap_up_hold", Severity.INFO, T0)).isFalse();
        assertThat(enb.matches("FTS.TO", Subscription.Source.PRICE, "PA02_seller_controlled_drop", Severity.WARNING, T0)).isFalse();
        Subscription muted = new Subscription(3, "*", Subscription.Source.PRICE, "*", Severity.INFO, "user", T0, T0.plusSeconds(3600), true);
        assertThat(muted.matches("IBM", Subscription.Source.PRICE, "M01_rsi_overbought", Severity.WATCH, T0)).isFalse();
        assertThat(muted.matches("IBM", Subscription.Source.PRICE, "M01_rsi_overbought", Severity.WATCH, T0.plusSeconds(7200))).isTrue();
    }

    @Test
    void calendarKnowsWeekendsHolidaysAndHours() {
        MarketCalendar cal = new MarketCalendar(Map.of(Exchange.TSX, Set.of(LocalDate.of(2026, 10, 12)),
                Exchange.NYSE, Set.of(LocalDate.of(2026, 11, 26))));
        assertThat(MarketCalendar.exchangeOf("ENB.TO")).isEqualTo(Exchange.TSX);
        assertThat(MarketCalendar.exchangeOf("IBM")).isEqualTo(Exchange.NYSE);
        assertThat(cal.isTradingDay(Exchange.TSX, LocalDate.of(2026, 10, 12))).isFalse(); // Canadian Thanksgiving
        assertThat(cal.isTradingDay(Exchange.NYSE, LocalDate.of(2026, 10, 12))).isTrue();
        assertThat(cal.isTradingDay(Exchange.NYSE, LocalDate.of(2026, 10, 17))).isFalse(); // Saturday
        assertThat(cal.isOpen(Exchange.NYSE, LocalDate.of(2026, 10, 19), LocalTime.of(9, 29))).isFalse();
        assertThat(cal.isOpen(Exchange.NYSE, LocalDate.of(2026, 10, 19), LocalTime.of(15, 59))).isTrue();
        assertThat(cal.previousTradingDay(Exchange.TSX, LocalDate.of(2026, 10, 13))).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(cal.covers(Exchange.TSX, 2026)).isTrue();
        assertThat(cal.covers(Exchange.TSX, 2028)).isFalse();
    }
}
