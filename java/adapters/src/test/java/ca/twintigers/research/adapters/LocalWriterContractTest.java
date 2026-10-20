package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.local.LocalPortfolioSource;
import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.PortfolioWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/** The writer contract against the local source's SQLite ledger. */
class LocalWriterContractTest extends PortfolioWriterContract {

    @TempDir
    Path dir;
    LocalPortfolioSource source;

    @BeforeEach
    void setUp() throws Exception {
        Path toml = dir.resolve("portfolio.toml");
        Files.writeString(toml, """
                [[accounts]]
                name = "Cash CAD"
                currency = "CAD"
                [[accounts]]
                name = "Cash USD"
                currency = "USD"
                [[accounts]]
                name = "RRSP"
                currency = "CAD"
                """);
        source = new LocalPortfolioSource(toml, new Database(dir.resolve("research.db")), LocalSourceContractTest.PRICES, "CAD",
                LocalSourceContractTest.CLOCK);
    }

    @Override
    PortfolioWriter writer() {
        return source;
    }

    @Override
    PortfolioSource reader() {
        return source;
    }
}
