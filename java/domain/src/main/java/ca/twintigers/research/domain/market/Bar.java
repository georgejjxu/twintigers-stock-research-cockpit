package ca.twintigers.research.domain.market;

import java.time.LocalDate;

/** One daily candle ("K-line"): open, high, low, close and volume for a trading day. */
public record Bar(LocalDate date, double open, double high, double low, double close, long volume) {

    public Bar {
        if (high < low) {
            throw new IllegalArgumentException("high < low on " + date);
        }
    }

    public double range() {
        return high - low;
    }

    public double body() {
        return Math.abs(close - open);
    }

    public double upperShadow() {
        return high - Math.max(open, close);
    }

    public double lowerShadow() {
        return Math.min(open, close) - low;
    }

    public boolean bullish() {
        return close > open;
    }

    public boolean bearish() {
        return close < open;
    }

    public double bodyMid() {
        return (open + close) / 2;
    }

    /** Where the close sits in the day's range: 0 = at the low, 1 = at the high (0.5 for a zero range). */
    public double closeLocation() {
        return range() == 0 ? 0.5 : (close - low) / range();
    }

    /** True range: the day's range extended to the previous close if the day gapped. */
    public double trueRange(Bar previous) {
        if (previous == null) {
            return range();
        }
        return Math.max(range(), Math.max(Math.abs(high - previous.close), Math.abs(low - previous.close)));
    }
}
