package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Benchmark rows: day, year-to-date and 12-month change, distance from the 52-week high, moving averages. */
class MarketServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T14:00:00Z"), ZoneId.of("America/Toronto"));

    /** 300 daily closes rising by 1 from 100, last one 2026-10-19; then a drop to 380 on the last day. */
    static final MarketData PRICES = new MarketData() {
        @Override
        public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
            if (symbol.equals("BAD")) {
                throw new DataSourceException("Yahoo Finance does not know the symbol BAD.");
            }
            List<Bar> out = new ArrayList<>();
            LocalDate d = LocalDate.of(2026, 10, 19).minusDays(399);
            for (int i = 0; i < 400; i++) {
                double c = i == 399 ? 380 : 100 + i;
                out.add(new Bar(d.plusDays(i), c, c + 1, c - 1, c, 1000));
            }
            return out;
        }

        @Override
        public String source() {
            return "test";
        }
    };

    @Test
    void benchmarkFigures() {
        MarketService s = new MarketService(PRICES, () -> List.of(), MarketService.parse("TEST=Test index;BAD=Missing"), CLOCK);
        MarketService.View v = s.view();
        MarketService.Benchmark b = v.benchmarks().getFirst();
        assertThat(b.name()).isEqualTo("Test index");
        assertThat(b.close()).isEqualTo(380.0);
        assertThat(b.dayPct()).isEqualTo(-23.69); // 380 / 498 - 1
        assertThat(b.fromHighPct()).isEqualTo(-23.85); // 52-week high 498 + 1 = 499
        assertThat(b.above50()).isFalse();
        assertThat(b.sma200()).isNotNull();
        assertThat(b.ytdPct()).isNotNull();
        assertThat(v.benchmarks().get(1).error()).contains("does not know");
        assertThat(MarketService.parse(" ")).isEmpty();
        assertThat(new MarketService(PRICES, () -> List.of(), Map.of(), CLOCK).view().benchmarks()).hasSize(10); // defaults
    }
}
