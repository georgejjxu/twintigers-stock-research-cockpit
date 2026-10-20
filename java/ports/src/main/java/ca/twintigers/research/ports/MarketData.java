package ca.twintigers.research.ports;

import ca.twintigers.research.domain.market.Bar;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily OHLCV bars. Symbols use Yahoo conventions (TSX listings end in .TO). Implementations may be a
 * single provider, a fallback chain, or a cache in front of either. A streaming (intraday) source would be a
 * separate port; this one is end-of-day only.
 */
public interface MarketData {

    /** Bars from {@code from} to {@code to} inclusive, oldest first. */
    List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException;

    /** Short name of the provider, for citations (e.g. "Yahoo Finance chart API"). */
    String source();
}
