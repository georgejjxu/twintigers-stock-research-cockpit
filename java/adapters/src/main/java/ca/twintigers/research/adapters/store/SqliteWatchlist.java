package ca.twintigers.research.adapters.store;

import ca.twintigers.research.domain.portfolio.WatchItem;
import ca.twintigers.research.ports.WatchlistStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The watch list in the local SQLite file (schema V3). */
public final class SqliteWatchlist implements WatchlistStore {

    private final Database db;
    private final Clock clock;

    public SqliteWatchlist(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public List<WatchItem> watchlist() {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("SELECT symbol, note, added_at FROM watchlist ORDER BY symbol");
             ResultSet rs = ps.executeQuery()) {
            List<WatchItem> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new WatchItem(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3))));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Watch list: " + e.getMessage(), e);
        }
    }

    @Override
    public WatchItem watch(String symbol, String note) {
        String s = symbol.strip().toUpperCase(Locale.ROOT);
        if (!s.matches("[A-Z0-9.=^-]{1,20}")) {
            throw new IllegalArgumentException("Not a valid symbol: " + symbol + " (Yahoo form, e.g. ENB.TO or IBM).");
        }
        Instant now = clock.instant();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO watchlist(symbol, note, added_at) "
                + "VALUES (?,?,?) ON CONFLICT(symbol) DO UPDATE SET note = excluded.note")) {
            ps.setString(1, s);
            ps.setString(2, note == null || note.isBlank() ? null : note.strip());
            ps.setString(3, now.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Watch list: " + e.getMessage(), e);
        }
        return watchlist().stream().filter(w -> w.symbol().equals(s)).findFirst().orElseThrow();
    }

    @Override
    public boolean unwatch(String symbol) {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM watchlist WHERE symbol = ?")) {
            ps.setString(1, symbol.strip().toUpperCase(Locale.ROOT));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Watch list: " + e.getMessage(), e);
        }
    }
}
