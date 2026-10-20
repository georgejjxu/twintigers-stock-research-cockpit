package ca.twintigers.research.adapters.store;

import org.flywaydb.core.Flyway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The local SQLite file (bars cache, alerts, outbox, heartbeat). Flyway migrates the schema on open. WAL journal
 * and a 10 s busy timeout let the scheduled worker and the MCP server use the file at the same time.
 */
public final class Database {

    private final String url;

    public Database(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create " + file.getParent() + ": " + e.getMessage(), e);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open " + file + ": " + e.getMessage(), e);
        }
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();
    }

    /** A consistent copy of the whole database (VACUUM INTO), safe while other processes use it. */
    public void backupTo(Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.deleteIfExists(target);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot write " + target + ": " + e.getMessage(), e);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("Backup to " + target + " failed: " + e.getMessage(), e);
        }
    }

    public Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("busy_timeout", "10000");
        return DriverManager.getConnection(url, p);
    }
}
