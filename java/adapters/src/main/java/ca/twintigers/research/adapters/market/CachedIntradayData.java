package ca.twintigers.research.adapters.market;

import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.domain.market.Candle;
import ca.twintigers.research.domain.market.Interval;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.IntradayData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Intraday bars stored by symbol and interval (schema V7). A request is answered from the store; the provider is
 * asked only for what is new since the last stored bar (re-reading that bar, which may still have been forming), at
 * most once a minute per symbol and interval, and never further back than the feed serves ({@link Interval#feedLimit}).
 * Stored bars are kept, so the history grows past the feed's window.
 */
public final class CachedIntradayData implements IntradayData {

    private final IntradayData provider;
    private final Database db;
    private final Clock clock;

    public CachedIntradayData(IntradayData provider, Database db, Clock clock) {
        this.provider = provider;
        this.db = db;
        this.clock = clock;
    }

    private record Log(Instant fetchedAt, ZoneId zone, String source, int delay) {
    }

    @Override
    public Series intraday(String symbol, Interval interval, Instant from) throws DataSourceException {
        Instant now = clock.instant();
        Log log = log(symbol, interval);
        if (log == null || log.fetchedAt().isBefore(now.minusSeconds(60))) {
            Instant feedStart = now.minus(interval.feedLimit()).plus(Duration.ofHours(1));
            Instant last = lastStored(symbol, interval);
            // first time: from the request (within the feed's window); later: just the tail since the last stored bar
            Instant ask = last == null ? from : last.minus(interval.size().multipliedBy(2));
            if (ask.isBefore(feedStart)) {
                ask = feedStart;
            }
            try {
                Series fresh = provider.intraday(symbol, interval, ask);
                save(symbol, interval, fresh);
                log = new Log(now, fresh.exchangeZone(), fresh.source() + ", stored locally", fresh.delayMinutes());
            } catch (DataSourceException e) {
                if (log == null) {
                    throw e; // nothing stored to fall back on
                }
            }
        }
        return new Series(symbol, interval, log.zone(), load(symbol, interval, from), log.source(), log.delay(), now);
    }

    private Log log(String symbol, Interval interval) throws DataSourceException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT fetched_at, zone, source, delay_min FROM intraday_fetch_log WHERE symbol=? AND interval=?")) {
            ps.setString(1, symbol);
            ps.setString(2, interval.code());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Log(Instant.parse(rs.getString(1)), ZoneId.of(rs.getString(2)), rs.getString(3), rs.getInt(4)) : null;
            }
        } catch (SQLException e) {
            throw new DataSourceException("Intraday store: " + e.getMessage(), e);
        }
    }

    private Instant lastStored(String symbol, Interval interval) throws DataSourceException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT max(start_ts) FROM intraday_bars WHERE symbol=? AND interval=?")) {
            ps.setString(1, symbol);
            ps.setString(2, interval.code());
            try (ResultSet rs = ps.executeQuery()) {
                long v = rs.next() ? rs.getLong(1) : 0;
                return rs.wasNull() || v == 0 ? null : Instant.ofEpochSecond(v);
            }
        } catch (SQLException e) {
            throw new DataSourceException("Intraday store: " + e.getMessage(), e);
        }
    }

    private void save(String symbol, Interval interval, Series s) throws DataSourceException {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO intraday_bars(symbol, interval, start_ts, open, high, low, close, volume) "
                    + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(symbol, interval, start_ts) DO UPDATE SET open=excluded.open, high=excluded.high, "
                    + "low=excluded.low, close=excluded.close, volume=excluded.volume")) {
                for (Candle k : s.candles()) {
                    ps.setString(1, symbol);
                    ps.setString(2, interval.code());
                    ps.setLong(3, k.start().getEpochSecond());
                    ps.setDouble(4, k.open());
                    ps.setDouble(5, k.high());
                    ps.setDouble(6, k.low());
                    ps.setDouble(7, k.close());
                    ps.setLong(8, k.volume());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO intraday_fetch_log(symbol, interval, fetched_at, zone, source, delay_min) "
                    + "VALUES (?,?,?,?,?,?) ON CONFLICT(symbol, interval) DO UPDATE SET fetched_at=excluded.fetched_at, zone=excluded.zone, "
                    + "source=excluded.source, delay_min=excluded.delay_min")) {
                ps.setString(1, symbol);
                ps.setString(2, interval.code());
                ps.setString(3, clock.instant().toString());
                ps.setString(4, s.exchangeZone().getId());
                ps.setString(5, s.source() + ", stored locally");
                ps.setInt(6, s.delayMinutes());
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new DataSourceException("Intraday store: " + e.getMessage(), e);
        }
    }

    private List<Candle> load(String symbol, Interval interval, Instant from) throws DataSourceException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("SELECT start_ts, open, high, low, close, volume "
                + "FROM intraday_bars WHERE symbol=? AND interval=? AND start_ts>=? ORDER BY start_ts")) {
            ps.setString(1, symbol);
            ps.setString(2, interval.code());
            ps.setLong(3, from.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                List<Candle> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Candle(Instant.ofEpochSecond(rs.getLong(1)), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getLong(6)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataSourceException("Intraday store: " + e.getMessage(), e);
        }
    }
}
