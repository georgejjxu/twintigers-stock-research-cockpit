package ca.twintigers.research.ports;

import java.time.Instant;

/** The latest available (usually delayed) trade price, for intraday threshold checks. */
public interface QuoteSource {

    record Quote(String symbol, double price, Instant time, String source) {
    }

    Quote latest(String symbol) throws DataSourceException;
}
