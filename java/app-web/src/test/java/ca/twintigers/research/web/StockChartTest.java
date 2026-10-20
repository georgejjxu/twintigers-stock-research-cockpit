package ca.twintigers.research.web;

import ca.twintigers.research.domain.market.Interval;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ranges and date boxes resolve to intraday bars (short, recent spans) or daily bars (everything else). */
class StockChartTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void rangesMapToBarsAndStartDates() {
        StockChart.Plan d1 = StockChart.plan("1D", null, null, null, TODAY);
        assertThat(d1.intraday()).isTrue();
        assertThat(d1.interval()).isEqualTo(Interval.M1);
        assertThat(d1.sessions()).isEqualTo(1);
        StockChart.Plan d5 = StockChart.plan("5d", null, null, null, TODAY);
        assertThat(d5.interval()).isEqualTo(Interval.M5);
        assertThat(d5.sessions()).isEqualTo(5);
        assertThat(StockChart.plan("5D", null, null, "15m", TODAY).interval()).isEqualTo(Interval.M15);
        assertThat(StockChart.plan("1M", null, null, null, TODAY).from()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(StockChart.plan("YTD", null, null, null, TODAY).from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(StockChart.plan(null, null, null, null, TODAY).from()).isEqualTo(LocalDate.of(2025, 10, 20)); // 1Y
        assertThat(StockChart.plan("MAX", null, null, null, TODAY).intraday()).isFalse();
        assertThatThrownBy(() -> StockChart.plan("7Y", null, null, null, TODAY)).hasMessageContaining("Range must be one of");
    }

    @Test
    void dateBoxesPickIntradayOnlyForShortRecentSpans() {
        StockChart.Plan week = StockChart.plan(null, "2026-10-13", "2026-10-16", null, TODAY);
        assertThat(week.intraday()).isTrue();
        assertThat(week.interval()).isEqualTo(Interval.M5);
        StockChart.Plan oldWeek = StockChart.plan(null, "2026-03-02", "2026-03-06", null, TODAY);
        assertThat(oldWeek.intraday()).isFalse(); // older than the feed keeps intraday bars
        StockChart.Plan quarter = StockChart.plan(null, "2026-06-01", null, null, TODAY);
        assertThat(quarter.intraday()).isFalse();
        assertThat(quarter.to()).isEqualTo(TODAY);
        assertThat(StockChart.plan(null, "2026-10-01", "2026-10-02", "1m", TODAY).interval()).isEqualTo(Interval.M5); // 1m: last 7 days only
        assertThatThrownBy(() -> StockChart.plan(null, "2026-10-10", "2026-10-01", null, TODAY)).hasMessageContaining("before");
    }
}
