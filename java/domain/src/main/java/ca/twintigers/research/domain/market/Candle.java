package ca.twintigers.research.domain.market;

import java.time.Instant;

/** One intraday bar: the interval starting at {@code start}. Daily analysis keeps using {@link Bar}. */
public record Candle(Instant start, double open, double high, double low, double close, long volume) {
}
