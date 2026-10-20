package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.LedgerBook;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Sync mirrors the source exactly and repeatably; parity reports what matches and what does not. */
class LedgerSyncTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T21:00:00Z"), ZoneId.of("America/Toronto"));
    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    /** A source holding fixed accounts, activities, holdings and (optionally) its own return figure. */
    static final class Source implements PortfolioSource {
        final List<Account> accounts = new ArrayList<>();
        final List<Activity> activities = new ArrayList<>();
        Portfolio portfolio = new Portfolio("CAD", List.of(), BigDecimal.ZERO);
        Performance performance;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Set<Capability> capabilities() {
            return performance == null ? EnumSet.of(Capability.ACCOUNTS, Capability.ACTIVITIES) : EnumSet.allOf(Capability.class);
        }

        @Override
        public Portfolio portfolio() {
            return portfolio;
        }

        @Override
        public List<Account> accounts() {
            return accounts;
        }

        @Override
        public List<Activity> activities() {
            return activities;
        }

        @Override
        public Optional<Performance> performance(String range) {
            return Optional.ofNullable(performance);
        }
    }

    /** In-memory ledger. */
    static final class Book implements LedgerBook {
        final Map<String, Account> accounts = new TreeMap<>();
        final Map<String, Activity> rows = new LinkedHashMap<>();
        final Map<String, String> names = new TreeMap<>();
        int writes;

        @Override
        public List<Account> ledgerAccounts() {
            return List.copyOf(accounts.values());
        }

        @Override
        public void putAccount(String name, String currency, BigDecimal cash) {
            accounts.put(name, new Account(name, currency, cash, null));
        }

        @Override
        public void removeAccount(String name) {
            accounts.remove(name);
        }

        @Override
        public void putInstrument(String symbol, String name, String currency) {
            names.put(symbol, name);
        }

        @Override
        public void putCashHistory(String account, List<ca.twintigers.research.domain.portfolio.CashBalance> points) {
        }

        @Override
        public void putProfile(ca.twintigers.research.domain.portfolio.InstrumentProfile profile) {
        }

        @Override
        public Map<String, Activity> mirrored() {
            return new LinkedHashMap<>(rows);
        }

        @Override
        public Activity putMirrored(String externalId, ActivityDraft d) throws DataSourceException {
            if (!accounts.containsKey(d.account())) {
                throw new DataSourceException("Unknown account \"" + d.account() + "\".");
            }
            writes++;
            Activity a = new Activity("local-" + externalId, d.date(), d.type(), d.account(), d.symbol(), d.quantity(), d.unitPrice(), d.fee(),
                    d.currency(), d.comment());
            rows.put(externalId, a);
            return a;
        }

        @Override
        public void removeMirrored(String externalId) {
            rows.remove(externalId);
        }
    }

    static Activity act(String id, String type, String account, String symbol, String qty, String price, String comment) {
        return new Activity(id, DAY, type, account, symbol, new BigDecimal(qty), new BigDecimal(price), BigDecimal.ZERO, "CAD", comment);
    }

    @Test
    void syncAddsUpdatesRemovesAndIsRepeatable() throws Exception {
        Source gf = new Source();
        gf.accounts.add(new Account("RRSP", "CAD", new BigDecimal("0"), null));
        gf.accounts.add(new Account("Cash CAD", "CAD", new BigDecimal("400000"), null));
        gf.activities.add(act("a1", "BUY", "RRSP", "ENB.TO", "980", "67.5", "Placeholder cost"));
        gf.activities.add(act("a2", "BUY", "RRSP", "FTS.TO", "500", "70", null));
        gf.activities.add(act("a3", "FEE", "RRSP", "ENB.TO", "1", "5", null));
        gf.portfolio = new Portfolio("CAD", List.of(new Portfolio.Position("ENB.TO", "Enbridge Inc.", "CAD", new BigDecimal("980"),
                new BigDecimal("68"), new BigDecimal("66640"), Map.of("RRSP", new BigDecimal("980")))), BigDecimal.ZERO);
        Book book = new Book();
        book.putAccount("Old account", "CAD", BigDecimal.ONE);
        LedgerSync sync = new LedgerSync();

        LedgerSync.Result first = sync.sync(gf, book);
        assertThat(first.added()).isEqualTo(2);
        assertThat(first.accountsRemoved()).isEqualTo(1);
        assertThat(first.skipped()).singleElement().asString().contains("type FEE");
        assertThat(book.accounts).containsOnlyKeys("RRSP", "Cash CAD");
        assertThat(book.names).containsEntry("ENB.TO", "Enbridge Inc.");

        int writes = book.writes;
        LedgerSync.Result again = sync.sync(gf, book);
        assertThat(again.unchanged()).isEqualTo(2);
        assertThat(book.writes).isEqualTo(writes); // nothing rewritten

        gf.activities.set(0, act("a1", "BUY", "RRSP", "ENB.TO", "980", "52.10", "real price"));
        gf.activities.remove(1);
        LedgerSync.Result third = sync.sync(gf, book);
        assertThat(third.updated()).isEqualTo(1);
        assertThat(third.removed()).isEqualTo(1);
        assertThat(book.rows).containsOnlyKeys("a1");
        assertThat(book.rows.get("a1").unitPrice()).isEqualByComparingTo("52.10");
    }

    @Test
    void parityMatchesIdenticalSourcesAndFlagsDifferences() throws Exception {
        Source gf = new Source();
        gf.accounts.add(new Account("RRSP", "CAD", new BigDecimal("100"), null));
        gf.activities.add(act("a1", "BUY", "RRSP", "ENB.TO", "10", "50", null));
        Portfolio.Position pos = new Portfolio.Position("ENB.TO", "Enbridge", "CAD", new BigDecimal("10"), new BigDecimal("55"),
                new BigDecimal("550.00"), Map.of("RRSP", new BigDecimal("10")));
        gf.portfolio = new Portfolio("CAD", List.of(pos), new BigDecimal("100"));
        gf.performance = new Performance("max", new BigDecimal("550"), new BigDecimal("500"), new BigDecimal("50"), new BigDecimal("10.00"), "fake");

        Source ledger = new Source();
        ledger.accounts.addAll(gf.accounts);
        ledger.activities.addAll(gf.activities);
        ledger.portfolio = new Portfolio("CAD", List.of(new Portfolio.Position("ENB.TO", "Enbridge", "CAD", new BigDecimal("10"),
                new BigDecimal("55.2"), new BigDecimal("552.00"), Map.of("RRSP", new BigDecimal("10")))), new BigDecimal("100"));
        MarketData closes = new MarketData() {
            @Override
            public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) {
                return List.of(new Bar(DAY, 50, 50, 50, 50, 1), new Bar(DAY.plusDays(1), 55, 55, 55, 55, 1));
            }

            @Override
            public String source() {
                return "test";
            }
        };
        PerformanceService ledgerReturns = new PerformanceService(ledger, closes, (s, f, t) -> List.of(), "CAD", CLOCK);

        SourceParity.Result ok = new SourceParity(CLOCK).compare(gf, ledger, ledgerReturns, List.of("max"));
        assertThat(ok.differences()).isZero();
        assertThat(ok.lines()).anySatisfy(l -> {
            assertThat(l.area()).isEqualTo("Return (%)");
            assertThat(l.status()).isEqualTo(SourceParity.Status.MATCH);
            assertThat(l.candidate()).isEqualTo("10.00 %");
        });
        assertThat(ok.markdown()).contains("no differences beyond the tolerances").contains("within tolerance");

        ledger.accounts.set(0, new Account("RRSP", "CAD", new BigDecimal("90"), null));
        SourceParity.Result bad = new SourceParity(CLOCK).compare(gf, ledger, ledgerReturns, List.of("max"));
        assertThat(bad.differences()).isEqualTo(1);
        assertThat(bad.markdown()).contains("**different**");
    }
}
