package ca.twintigers.research.adapters.store;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.ports.BarStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link BarStore} in a local SQLite file; the schema is created and migrated by Flyway on first use. */
public final class SqliteBarStore implements BarStore {

    private final Database db;

    public SqliteBarStore(Database db) {
        this.db = db;
    }

    /** Convenience for tests and tools: opens (and migrates) the file. */
    public SqliteBarStore(Path file) {
        this(new Database(file));
    }

    private Connection connect() throws SQLException {
        return db.connect();
    }

    @Override
    public List<Bar> load(String symbol, LocalDate from, LocalDate to) {
        String sql = "SELECT date, open, high, low, close, volume FROM bars WHERE symbol = ? AND date >= ? AND date <= ? ORDER BY date";
        List<Bar> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Bar(LocalDate.parse(rs.getString(1)), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getLong(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Bar cache read failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public void save(String symbol, String source, List<Bar> bars, LocalDate requestedFrom, LocalDate requestedTo, Instant fetchedAt) {
        String upsert = "INSERT INTO bars(symbol, date, open, high, low, close, volume, source) VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(symbol, date) DO UPDATE SET open=excluded.open, high=excluded.high, low=excluded.low, "
                + "close=excluded.close, volume=excluded.volume, source=excluded.source";
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                for (Bar b : bars) {
                    ps.setString(1, symbol);
                    ps.setString(2, b.date().toString());
                    ps.setDouble(3, b.open());
                    ps.setDouble(4, b.high());
                    ps.setDouble(5, b.low());
                    ps.setDouble(6, b.close());
                    ps.setLong(7, b.volume());
                    ps.setString(8, source);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            {
                LocalDate from = requestedFrom;
                LocalDate to = requestedTo;
                Optional<FetchInfo> prev = lastFetch(c, symbol);
                if (prev.isPresent()) {
                    from = prev.get().from().isBefore(from) ? prev.get().from() : from;
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fetch_log(symbol, fetched_at, range_from, range_to, source) "
                        + "VALUES (?,?,?,?,?) ON CONFLICT(symbol) DO UPDATE SET fetched_at=excluded.fetched_at, "
                        + "range_from=excluded.range_from, range_to=excluded.range_to, source=excluded.source")) {
                    ps.setString(1, symbol);
                    ps.setString(2, fetchedAt.toString());
                    ps.setString(3, from.toString());
                    ps.setString(4, to.toString());
                    ps.setString(5, source);
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Bar cache write failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<FetchInfo> lastFetch(String symbol) {
        try (Connection c = connect()) {
            return lastFetch(c, symbol);
        } catch (SQLException e) {
            throw new IllegalStateException("Bar cache read failed: " + e.getMessage(), e);
        }
    }

    private static Optional<FetchInfo> lastFetch(Connection c, String symbol) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT fetched_at, range_from, range_to, source FROM fetch_log WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FetchInfo(Instant.parse(rs.getString(1)), LocalDate.parse(rs.getString(2)),
                        LocalDate.parse(rs.getString(3)), rs.getString(4)));
            }
        }
    }
}
