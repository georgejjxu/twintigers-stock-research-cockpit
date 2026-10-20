package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.local.LocalPortfolioSource;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The contract against the local source: portfolio.toml + stubbed market prices + transactions in the SQLite ledger. */
class LocalSourceContractTest extends PortfolioSourceContract {

    @TempDir
    Path dir;
    Path toml;
    ca.twintigers.research.adapters.store.Database db;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T20:30:00Z"), ZoneId.of("America/Toronto"));

    static final MarketData PRICES = new MarketData() {
        final Map<String, Double> close = Map.of("ENB.TO", 68.04, "ENB", 48.10, "USDCAD=X", 1.4122);

        @Override
        public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
            Double c = close.get(symbol);
            if (c == null) {
                throw new DataSourceException("unknown " + symbol);
            }
            return List.of(new Bar(LocalDate.of(2026, 10, 19), c, c, c, c, 1000));
        }

        @Override
        public String source() {
            return "stub";
        }
    };

    @BeforeEach
    void files() throws Exception {
        toml = dir.resolve("portfolio.toml");
        Files.writeString(toml, """
                as_of = 2026-10-19
                [[accounts]]
                name = "Cash CAD"
                currency = "CAD"
                holdings = [ { ticker = "ENB", exchange = "TSX", currency = "CAD", shares = 1100, name = "Enbridge Inc." } ]
                cash = [ { currency = "CAD", amount = 400000, kind = "money market fund" } ]

                [[accounts]]
                name = "Cash USD"
                currency = "USD"
                holdings = [ { ticker = "ENB", exchange = "NYSE", currency = "USD", shares = 800, name = "Enbridge Inc." } ]
                cash = [ { currency = "USD", amount = 70000, kind = "cash" } ]

                [[accounts]]
                name = "RRSP"
                currency = "CAD"
                holdings = [ { ticker = "ENB", exchange = "TSX", currency = "CAD", shares = 980, name = "Enbridge Inc." } ]
                """);
        db = new ca.twintigers.research.adapters.store.Database(dir.resolve("research.db"));
        var w = new LocalPortfolioSource(toml, db, PRICES, "CAD", CLOCK);
        w.create(draft("BUY", "Cash USD", "ENB", "2026-10-13", "800", "47.35", "USD", null));
        w.create(draft("BUY", "Cash CAD", "ENB.TO", "2026-10-14", "1100", "67.50", "CAD", "placeholder cost"));
        w.create(draft("BUY", "RRSP", "ENB.TO", "2026-10-14", "980", "67.50", "CAD", null));
        w.create(draft("DIVIDEND", "RRSP", "ENB.TO", "2026-10-15", "980", "0.9425", "CAD", "quarterly dividend"));
    }

    static ca.twintigers.research.domain.portfolio.ActivityDraft draft(String type, String account, String symbol, String date,
                                                                       String qty, String price, String cur, String comment) {
        return new ca.twintigers.research.domain.portfolio.ActivityDraft(type, account, symbol, LocalDate.parse(date),
                new java.math.BigDecimal(qty), new java.math.BigDecimal(price), java.math.BigDecimal.ZERO, cur, comment);
    }

    @Override
    PortfolioSource source() {
        return new LocalPortfolioSource(toml, db, PRICES, "CAD", CLOCK);
    }

    @Test
    void convertsUsdWithTheFxPairAndReportsNoPerformance() throws Exception {
        var p = source().portfolio();
        assertThat(p.positions().get(1).valueInBase()).isEqualByComparingTo("54341.46"); // 800 × 48.10 × 1.4122
        assertThat(p.cashInBase()).isEqualByComparingTo("498854.00");                     // 400,000 + 70,000 × 1.4122
        assertThat(source().capabilities()).doesNotContain(PortfolioSource.Capability.PERFORMANCE);
    }

    @Test
    void withAnEmptyLedgerHoldingsComeFromTheToml() throws Exception {
        var empty = new ca.twintigers.research.adapters.store.Database(dir.resolve("empty.db"));
        var s = new LocalPortfolioSource(toml, empty, PRICES, "CAD", CLOCK);
        assertThat(s.activities()).isEmpty();
        assertThat(s.portfolio().positions().getFirst().quantity()).isEqualByComparingTo("2080");
    }

    @Test
    void aSellReducesTheHoldingAndASellOutRemovesIt() throws Exception {
        var w = new LocalPortfolioSource(toml, db, PRICES, "CAD", CLOCK);
        w.create(draft("SELL", "Cash USD", "ENB", "2026-10-16", "800", "48.10", "USD", null));
        assertThat(w.portfolio().positions()).extracting(ca.twintigers.research.domain.portfolio.Portfolio.Position::symbol)
                .containsExactly("ENB.TO");
    }

    @Test
    void badTomlIsAClearError() throws Exception {
        Files.writeString(toml, "[[accounts]\nname = ");
        assertThatThrownBy(() -> source().portfolio()).isInstanceOf(DataSourceException.class).hasMessageContaining("not valid TOML");
    }
}
