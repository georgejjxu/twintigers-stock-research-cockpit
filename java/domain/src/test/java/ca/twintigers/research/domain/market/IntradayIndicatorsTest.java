package ca.twintigers.research.domain.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Session VWAP restarts each exchange day; intervals parse from their codes. */
class IntradayIndicatorsTest {

    static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    @Test
    void vwapIsVolumeWeightedAndRestartsEachSession() {
        List<Candle> c = List.of(
                new Candle(Instant.parse("2026-10-19T13:30:00Z"), 10, 12, 9, 9, 100),    // typical 10
                new Candle(Instant.parse("2026-10-19T13:35:00Z"), 9, 21, 19, 20, 300),   // typical 20
                new Candle(Instant.parse("2026-10-20T13:30:00Z"), 30, 31, 29, 30, 50),   // next session: typical 30
                new Candle(Instant.parse("2026-10-20T13:35:00Z"), 30, 31, 29, 30, 0));
        double[] v = IntradayIndicators.vwap(c, TORONTO);
        assertThat(v[0]).isCloseTo(10.0, within(1e-9));
        assertThat(v[1]).isCloseTo(17.5, within(1e-9)); // (10*100 + 20*300) / 400
        assertThat(v[2]).isCloseTo(30.0, within(1e-9));
        assertThat(v[3]).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void intervalsParse() {
        assertThat(Interval.parse("5m")).isEqualTo(Interval.M5);
        assertThat(Interval.parse(" 1H ")).isEqualTo(Interval.H1);
        assertThatThrownBy(() -> Interval.parse("2d")).hasMessageContaining("1m, 5m, 15m, 30m or 1h");
    }
}
