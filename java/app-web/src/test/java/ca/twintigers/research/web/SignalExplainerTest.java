package ca.twintigers.research.web;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.domain.rules.Severity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The explanation shows the bars a pattern used, the levels it compared against and its numbers in words. */
class SignalExplainerTest {

    static final LocalDate D = LocalDate.of(2026, 10, 1);

    static List<Bar> bars(int n) {
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Bar(D.plusDays(i), 100, 101, 99, 100, 1_000_000));
        }
        return out;
    }

    @Test
    void engulfingUsesTwoBarsAndThePreviousClose() {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("prev_open", 99.5);
        ev.put("prev_close", 100.5);
        ev.put("body", 2.1);
        ev.put("volume", 2_500_000L);
        Signal g = new Signal("ENB.TO", "K01_bearish_engulfing", "Bearish engulfing", Severity.INFO, Signal.Direction.BEARISH,
                D.plusDays(5), "Bearish engulfing after an up-trend.", ev);
        Map<String, Object> x = SignalExplainer.explain(g, bars(10), "Today's body engulfs yesterday's.");
        assertThat(x.get("days")).isEqualTo(List.of("2026-10-05", "2026-10-06"));
        assertThat(x.get("lines")).isEqualTo(List.of(Map.of("price", 100.5, "title", "previous close")));
        assertThat(x.get("facts")).isEqualTo(List.of(List.of("Previous open", "99.5"), List.of("Previous close", "100.5"),
                List.of("Body (open to close)", "2.1"), List.of("Volume", "2,500,000")));
        assertThat(x.get("definition")).isEqualTo("Today's body engulfs yesterday's.");
        assertThat(x.get("why")).isEqualTo("Bearish engulfing after an up-trend.");
    }

    @Test
    void supportBreakShowsTheLevelItsBufferAndWhereItHeld() {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("level", 98.0);
        ev.put("buffer", 0.5);
        ev.put("level_swing_dates", List.of("2026-09-10", "2026-09-20"));
        Signal g = new Signal("ENB.TO", "PA06_support_breakdown", "Close below support", Severity.INFO, Signal.Direction.BEARISH,
                D.plusDays(3), "Closed below support.", ev);
        Map<String, Object> x = SignalExplainer.explain(g, bars(5), null);
        assertThat(x.get("days")).isEqualTo(List.of("2026-10-04", "2026-09-10", "2026-09-20"));
        assertThat(x.get("lines")).isEqualTo(List.of(Map.of("price", 98.0, "title", "support / resistance"),
                Map.of("price", 97.5, "title", "level − buffer")));
    }
}
