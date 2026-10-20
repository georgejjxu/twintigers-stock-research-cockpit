package ca.twintigers.research.adapters.store;

import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.alerts.AlertState;
import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.domain.alerts.Subscription;
import ca.twintigers.research.domain.rules.Severity;
import ca.twintigers.research.ports.AlertStore;
import ca.twintigers.research.ports.HoldingsCache;
import ca.twintigers.research.ports.Outbox;
import ca.twintigers.research.ports.RunLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Alerts, subscriptions, outbox, run log and holdings cache in the local SQLite file (schema V2). */
public final class SqliteAlertDb implements AlertStore, Outbox, RunLog, HoldingsCache {

    private final Database db;

    public SqliteAlertDb(Database db) {
        this.db = db;
    }

    @FunctionalInterface
    private interface Sql<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T tx(Sql<T> body) {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            T out = body.run(c);
            c.commit();
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Alert database: " + e.getMessage(), e);
        }
    }

    private static String s(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Instant i(String s) {
        return s == null ? null : Instant.parse(s);
    }

    private static void setDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.REAL);
        } else {
            ps.setDouble(idx, v);
        }
    }

    private static Double getDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    // --- alerts -------------------------------------------------------------------------------------------

    @Override
    public Alert addAlert(Alert a) {
        long id = tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO alerts(kind, symbol, level, pct, reference, atr_multiple, "
                    + "since, note, created_at, muted_until, active, state, state_since, cooldown_until) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, a.kind().name());
                ps.setString(2, a.symbol());
                setDouble(ps, 3, a.level());
                setDouble(ps, 4, a.pct());
                setDouble(ps, 5, a.reference());
                setDouble(ps, 6, a.atrMultiple());
                ps.setString(7, a.since() == null ? null : a.since().toString());
                ps.setString(8, a.note());
                ps.setString(9, s(a.createdAt()));
                ps.setString(10, s(a.mutedUntil()));
                ps.setInt(11, a.active() ? 1 : 0);
                ps.setString(12, a.state().status().name());
                ps.setString(13, s(a.state().since()));
                ps.setString(14, s(a.state().cooldownUntil()));
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    return k.getLong(1);
                }
            }
        });
        return alert(id).orElseThrow();
    }

    private static Alert alertRow(ResultSet rs) throws SQLException {
        String since = rs.getString("since");
        return new Alert(rs.getLong("id"), Alert.Kind.valueOf(rs.getString("kind")), rs.getString("symbol"),
                getDouble(rs, "level"), getDouble(rs, "pct"), getDouble(rs, "reference"), getDouble(rs, "atr_multiple"),
                since == null ? null : LocalDate.parse(since), rs.getString("note"), i(rs.getString("created_at")),
                i(rs.getString("muted_until")), rs.getInt("active") == 1,
                new AlertState(AlertState.Status.valueOf(rs.getString("state")), i(rs.getString("state_since")),
                        i(rs.getString("cooldown_until"))));
    }

    @Override
    public List<Alert> alerts(boolean includeInactive) {
        return tx(c -> {
            List<Alert> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM alerts" + (includeInactive ? "" : " WHERE active = 1") + " ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(alertRow(rs));
                }
            }
            return out;
        });
    }

    @Override
    public Optional<Alert> alert(long id) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM alerts WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(alertRow(rs)) : Optional.<Alert>empty();
                }
            }
        });
    }

    @Override
    public void updateState(long id, AlertState st) {
        update("UPDATE alerts SET state = ?, state_since = ?, cooldown_until = ? WHERE id = ?",
                st.status().name(), s(st.since()), s(st.cooldownUntil()), id);
    }

    @Override
    public boolean removeAlert(long id) {
        return update("UPDATE alerts SET active = 0 WHERE id = ? AND active = 1", id) > 0;
    }

    @Override
    public boolean muteAlert(long id, Instant until) {
        return update("UPDATE alerts SET muted_until = ? WHERE id = ? AND active = 1", s(until), id) > 0;
    }

    private int update(String sql, Object... args) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int k = 0; k < args.length; k++) {
                    ps.setObject(k + 1, args[k]);
                }
                return ps.executeUpdate();
            }
        });
    }

    // --- subscriptions ------------------------------------------------------------------------------------

    @Override
    public Subscription addSubscription(Subscription sub) {
        long id = tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO subscriptions(scope, source, rule, min_severity, origin, "
                    + "created_at, muted_until, active) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, sub.scope());
                ps.setString(2, sub.source().name());
                ps.setString(3, sub.rule());
                ps.setString(4, sub.minSeverity().name());
                ps.setString(5, sub.origin());
                ps.setString(6, s(sub.createdAt()));
                ps.setString(7, s(sub.mutedUntil()));
                ps.setInt(8, sub.active() ? 1 : 0);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    return k.getLong(1);
                }
            }
        });
        return subscriptions(true).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    @Override
    public List<Subscription> subscriptions(boolean includeInactive) {
        return tx(c -> {
            List<Subscription> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM subscriptions" + (includeInactive ? "" : " WHERE active = 1") + " ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Subscription(rs.getLong("id"), rs.getString("scope"), Subscription.Source.valueOf(rs.getString("source")),
                            rs.getString("rule"), Severity.valueOf(rs.getString("min_severity")), rs.getString("origin"),
                            i(rs.getString("created_at")), i(rs.getString("muted_until")), rs.getInt("active") == 1));
                }
            }
            return out;
        });
    }

    @Override
    public boolean removeSubscription(long id) {
        return update("UPDATE subscriptions SET active = 0 WHERE id = ? AND active = 1", id) > 0;
    }

    @Override
    public boolean muteSubscription(long id, Instant until) {
        return update("UPDATE subscriptions SET muted_until = ? WHERE id = ? AND active = 1", s(until), id) > 0;
    }

    @Override
    public int removeDefaultSubscriptions() {
        return update("UPDATE subscriptions SET active = 0 WHERE origin = 'default' AND active = 1");
    }

    // --- outbox -------------------------------------------------------------------------------------------

    @Override
    public int enqueue(Notification n, List<String> channels, Instant now) {
        return tx(c -> {
            int created = 0;
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO outbox(dedupe_key, channel, title, body, priority, "
                    + "created_at, next_attempt_at) VALUES (?,?,?,?,?,?,?)")) {
                for (String ch : channels) {
                    ps.setString(1, n.dedupeKey());
                    ps.setString(2, ch);
                    ps.setString(3, n.title());
                    ps.setString(4, n.body());
                    ps.setString(5, n.priority().name());
                    ps.setString(6, s(now));
                    ps.setString(7, s(now));
                    created += ps.executeUpdate();
                }
            }
            return created;
        });
    }

    private static Entry entryRow(ResultSet rs) throws SQLException {
        return new Entry(rs.getLong("id"), rs.getString("dedupe_key"), rs.getString("channel"), rs.getString("title"),
                rs.getString("body"), Notification.Priority.valueOf(rs.getString("priority")), i(rs.getString("created_at")),
                rs.getInt("attempts"), i(rs.getString("next_attempt_at")), i(rs.getString("sent_at")), rs.getString("last_error"));
    }

    @Override
    public List<Entry> due(Instant now) {
        return tx(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM outbox WHERE sent_at IS NULL AND gave_up = 0 "
                    + "AND next_attempt_at <= ? ORDER BY id")) {
                ps.setString(1, s(now));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(entryRow(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void markSent(long id, Instant at) {
        update("UPDATE outbox SET sent_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ? AND sent_at IS NULL", s(at), id);
    }

    @Override
    public void markFailed(long id, String error, Instant nextAttemptAt, boolean giveUp) {
        update("UPDATE outbox SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?, gave_up = ? WHERE id = ?",
                error, s(nextAttemptAt), giveUp ? 1 : 0, id);
    }

    @Override
    public List<Entry> recentEntries(int limit) {
        return tx(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM outbox ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(entryRow(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public long pendingCount() {
        return tx(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL AND gave_up = 0")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // --- run log ------------------------------------------------------------------------------------------

    @Override
    public long start(String mode, Instant at) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO runs(mode, started_at) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, mode);
                ps.setString(2, s(at));
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    return k.getLong(1);
                }
            }
        });
    }

    @Override
    public void finish(long id, Instant at, boolean ok, int symbols, int notifications, String detail) {
        update("UPDATE runs SET finished_at = ?, ok = ?, symbols = ?, notifications = ?, detail = ? WHERE id = ?",
                s(at), ok ? 1 : 0, symbols, notifications, detail, id);
    }

    private static Run runRow(ResultSet rs) throws SQLException {
        return new Run(rs.getLong("id"), rs.getString("mode"), i(rs.getString("started_at")), i(rs.getString("finished_at")),
                rs.getInt("ok") == 1, rs.getInt("symbols"), rs.getInt("notifications"), rs.getString("detail"));
    }

    @Override
    public Optional<Run> lastSuccessfulEvaluation() {
        return tx(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM runs WHERE ok = 1 AND "
                    + "mode IN ('close','intraday') AND finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1")) {
                return rs.next() ? Optional.of(runRow(rs)) : Optional.<Run>empty();
            }
        });
    }

    @Override
    public List<Run> recentRuns(int limit) {
        return tx(c -> {
            List<Run> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM runs ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(runRow(rs));
                    }
                }
            }
            return out;
        });
    }

    // --- holdings cache -----------------------------------------------------------------------------------

    @Override
    public void save(List<String> symbols, Instant at) {
        tx(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM holdings_cache");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO holdings_cache(symbol, saved_at) VALUES (?,?)")) {
                for (String sym : symbols) {
                    ps.setString(1, sym);
                    ps.setString(2, s(at));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public Optional<Saved> load() {
        return tx(c -> {
            List<String> syms = new ArrayList<>();
            Instant at = null;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT symbol, saved_at FROM holdings_cache ORDER BY symbol")) {
                while (rs.next()) {
                    syms.add(rs.getString(1));
                    at = i(rs.getString(2));
                }
            }
            return syms.isEmpty() ? Optional.<Saved>empty() : Optional.of(new Saved(syms, at));
        });
    }
}
