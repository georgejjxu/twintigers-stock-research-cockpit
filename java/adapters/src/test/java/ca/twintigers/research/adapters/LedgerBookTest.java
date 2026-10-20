package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.local.LocalPortfolioSource;
import ca.twintigers.research.adapters.market.YahooChartClient;
import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.domain.market.CorporateAction;
import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.ActivityDraft;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.CorporateActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The local ledger as a mirror target: its own accounts, mirrored rows by source id, split-adjusted holdings. */
class LedgerBookTest {

    @TempDir
    Path dir;

    LocalPortfolioSource source(CorporateActions actions) {
        // no portfolio.toml at all: the ledger's own accounts are enough
        return new LocalPortfolioSource(dir.resolve("missing.toml"), new Database(dir.resolve("research.db")), LocalSourceContractTest.PRICES,
                actions, "CAD", LocalSourceContractTest.CLOCK);
    }

    static ActivityDraft buy(String account, String symbol, String qty, String price, String comment) {
        return new ActivityDraft("BUY", account, symbol, LocalDate.of(2026, 10, 14), new BigDecimal(qty), new BigDecimal(price),
                BigDecimal.ZERO, "CAD", comment);
    }

    @Test
    void ledgerAccountsReplaceTheTomlAndMirroredRowsAreKeyedBySourceId() throws Exception {
        LocalPortfolioSource s = source(null);
        assertThat(s.accounts()).isEmpty(); // a new installation without portfolio.toml starts with no accounts
        assertThat(s.addAccount("TFSA", "cad", new BigDecimal("100")).currency()).isEqualTo("CAD");
        assertThatThrownBy(() -> s.addAccount("tfsa", "CAD", BigDecimal.ZERO)).hasMessageContaining("already an account");
        assertThatThrownBy(() -> s.addAccount("X", "dollars", BigDecimal.ZERO)).hasMessageContaining("3-letter");
        s.removeAccount("TFSA");

        s.putAccount("RRSP", "CAD", new BigDecimal("1500.25"));
        s.putAccount("Cash USD", "usd", new BigDecimal("70000"));
        assertThat(s.accounts()).extracting(Account::name, Account::cashBalance)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Cash USD", new BigDecimal("70000.00")),
                        org.assertj.core.groups.Tuple.tuple("RRSP", new BigDecimal("1500.25")));
        assertThat(s.accountNames()).containsExactly("Cash USD", "RRSP");

        s.putMirrored("gf-1", buy("RRSP", "ENB.TO", "980", "67.5", "Placeholder cost"));
        s.putMirrored("gf-1", buy("RRSP", "ENB.TO", "980", "52.1", "real price")); // same source id: updated, not added
        s.create(buy("RRSP", "ENB.TO", "20", "60", "entered locally"));
        assertThat(s.activities()).hasSize(2);
        assertThat(s.mirrored()).containsOnlyKeys("gf-1");
        assertThat(s.mirrored().get("gf-1").unitPrice()).isEqualByComparingTo("52.1");

        s.putInstrument("ENB.TO", "Enbridge Inc.", "CAD");
        Portfolio p = s.portfolio();
        assertThat(p.positions()).singleElement().satisfies(x -> {
            assertThat(x.name()).isEqualTo("Enbridge Inc.");
            assertThat(x.quantity()).isEqualByComparingTo("1000");
        });

        s.removeMirrored("gf-1");
        assertThat(s.activities()).singleElement().satisfies(a -> assertThat(a.comment()).isEqualTo("entered locally"));
        s.removeAccount("Cash USD");
        assertThat(s.accountNames()).containsExactly("RRSP");
    }

    @Test
    void unknownSymbolsGetTheirCompanyNameOnceFromTheSearch() throws Exception {
        int[] calls = {0};
        LocalPortfolioSource s = source(null).withNameLookup((q, max) -> {
            calls[0]++;
            return List.of(new ca.twintigers.research.ports.SymbolSearch.Match("ENB.TO", "Enbridge Inc.", "Toronto", "Stock"));
        });
        s.putAccount("RRSP", "CAD", BigDecimal.ZERO);
        s.create(buy("RRSP", "ENB.TO", "10", "60", null));
        assertThat(s.portfolio().positions().getFirst().name()).isEqualTo("Enbridge Inc.");
        assertThat(s.portfolio().positions().getFirst().name()).isEqualTo("Enbridge Inc.");
        assertThat(calls[0]).isEqualTo(1); // stored after the first lookup
    }

    @Test
    void holdingsAreInTodaysShareUnitsAfterASplit() throws Exception {
        CorporateActions splits = (symbol, from, to) -> List.of(CorporateAction.split(symbol, LocalDate.of(2026, 10, 16), 3, 1));
        LocalPortfolioSource s = source(splits);
        s.putAccount("RRSP", "CAD", BigDecimal.ZERO);
        s.create(buy("RRSP", "ENB.TO", "100", "67.5", null));
        assertThat(s.portfolio().positions().getFirst().quantity()).isEqualByComparingTo("300");
    }

    @Test
    void yahooIntradayBarsSkipGapsAndKeepTheExchangeZone() throws Exception {
        String body = """
                {"chart":{"result":[{"meta":{"exchangeTimezoneName":"America/Toronto"},
                 "timestamp":[1792416600,1792416900,1792417200],
                 "indicators":{"quote":[{"open":[66.9,null,66.7],"high":[67.0,null,66.8],"low":[66.8,null,66.6],
                                         "close":[66.85,null,66.75],"volume":[120000,null,90000]}]}}],"error":null}}""";
        var s = YahooChartClient.parseIntraday(new ObjectMapper().readTree(body), "ENB.TO",
                ca.twintigers.research.domain.market.Interval.M5, java.time.Instant.parse("2026-10-19T20:00:00Z"));
        assertThat(s.candles()).hasSize(2);
        assertThat(s.candles().getFirst().start()).isEqualTo(java.time.Instant.ofEpochSecond(1792416600));
        assertThat(s.exchangeZone().getId()).isEqualTo("America/Toronto");
        assertThat(s.delayMinutes()).isEqualTo(15);
    }

    @Test
    void yahooSearchKeepsStocksAndEtfs() throws Exception {
        String body = """
                {"quotes":[{"symbol":"RY.TO","longname":"Royal Bank of Canada","exchDisp":"Toronto","quoteType":"EQUITY"},
                 {"symbol":"RY250117C00100000","shortname":"RY Jan 2025 call","exchDisp":"OPR","quoteType":"OPTION"},
                 {"symbol":"ZEB.TO","shortname":"BMO EQUAL WEIGHT BANKS","exchDisp":"Toronto","quoteType":"ETF"}]}""";
        var m = YahooChartClient.parseSearch(new ObjectMapper().readTree(body), 10);
        assertThat(m).extracting(x -> x.symbol() + "|" + x.name() + "|" + x.type())
                .containsExactly("RY.TO|Royal Bank of Canada|Stock", "ZEB.TO|BMO EQUAL WEIGHT BANKS|ETF");
    }

    @Test
    void yahooEventsBecomeSplitsAndDividends() throws Exception {
        String body = """
                {"chart":{"result":[{"meta":{"exchangeTimezoneName":"America/Toronto"},
                 "events":{"dividends":{"1739491200":{"amount":0.9425,"date":1739491200}},
                           "splits":{"1621900800":{"date":1621900800,"numerator":2,"denominator":1,"splitRatio":"2:1"}}}}],"error":null}}""";
        List<CorporateAction> a = YahooChartClient.parseActions(new ObjectMapper().readTree(body), "ENB.TO");
        assertThat(a).extracting(CorporateAction::kind).containsExactly(CorporateAction.Kind.SPLIT, CorporateAction.Kind.DIVIDEND);
        assertThat(a.getFirst().splitRatio()).isEqualTo(2.0);
        assertThat(a.get(1).dividendPerShare()).isEqualTo(0.9425);
        assertThat(a.get(1).date()).isEqualTo(LocalDate.of(2025, 2, 13));
    }
}
