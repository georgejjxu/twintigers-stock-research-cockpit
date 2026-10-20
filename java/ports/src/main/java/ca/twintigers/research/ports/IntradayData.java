package ca.twintigers.research.ports;

import ca.twintigers.research.domain.market.Candle;
import ca.twintigers.research.domain.market.Interval;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Intraday bars for the Cockpit's intraday chart. Today: Yahoo Finance (free, delayed about 15 minutes, regular
 * session only). A real-time provider (a broker API or a paid feed) implements the same port; a push stream would be
 * a separate port on top. The price rules and alerts keep using daily bars and quotes.
 */
public interface IntradayData {

    /**
     * @param exchangeZone the listing's exchange time zone (America/Toronto, America/New_York), for display
     * @param delayMinutes how far behind real time the latest bar is (0 for a real-time feed)
     */
    record Series(String symbol, Interval interval, ZoneId exchangeZone, List<Candle> candles, String source, int delayMinutes,
                  Instant asOf) {
    }

    /** Bars from {@code from} to now, oldest first; the newest one may still be forming. */
    Series intraday(String symbol, Interval interval, Instant from) throws DataSourceException;
}
