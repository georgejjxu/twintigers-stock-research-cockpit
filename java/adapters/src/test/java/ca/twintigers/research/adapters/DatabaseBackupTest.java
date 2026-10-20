package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The nightly copy is a complete, openable database, and a second copy the same day replaces the first. */
class DatabaseBackupTest {

    @Test
    void backupIsACompleteDatabase(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("research.db"));
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (x INTEGER)");
            s.execute("INSERT INTO t VALUES (1), (2), (3)");
        }
        Path copy = dir.resolve("backups").resolve("research-2026-10-19.db");
        db.backupTo(copy);
        db.backupTo(copy); // same day again: replaced, not an error

        Database restored = new Database(copy);
        try (Connection c = restored.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM t")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getInt(1)).isEqualTo(3);
        }
    }
}
