package ca.twintigers.research.adapters;

import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.PortfolioWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour every {@link PortfolioWriter} must have, run against each implementation (local ledger, Ghostfolio).
 * "Today" is 2026-10-19 for every subclass. Accounts: Cash CAD, Cash USD, RRSP.
 */
abstract class PortfolioWriterContract {

    /** A writer and the source that reads what it wrote (usually the same object). */
    abstract PortfolioWriter writer() throws Exception;

    abstract PortfolioSource reader() throws Exception;

    static ActivityDraft buy(String date, String qty, String price) {
        return new ActivityDraft("buy", "RRSP", "fts.to", LocalDate.parse(date), new BigDecimal(qty), new BigDecimal(price),
                new BigDecimal("4.95"), "cad", "  entered in the Cockpit ");
    }

    @Test
    void listsAccountsAndIsEnabled() throws Exception {
        assertThat(writer().enabled()).isTrue();
        assertThat(writer().accountNames()).contains("Cash CAD", "Cash USD", "RRSP");
    }

    @Test
    void setsAnAccountsCashAndRefusesBadInput() throws Exception {
        PortfolioWriter w = writer();
        ca.twintigers.research.domain.portfolio.Account a = w.setCash("RRSP", new BigDecimal("1234.50"));
        assertThat(a.name()).isEqualTo("RRSP");
        assertThat(a.cashBalance()).isEqualByComparingTo("1234.50");
        assertThat(reader().accounts()).filteredOn(x -> x.name().equals("RRSP")).singleElement()
                .satisfies(x -> assertThat(x.cashBalance()).isEqualByComparingTo("1234.50"));
        assertThatThrownBy(() -> w.setCash("Nope", BigDecimal.ONE)).isInstanceOf(DataSourceException.class).hasMessageContaining("Unknown account");
        assertThatThrownBy(() -> w.setCash("RRSP", new BigDecimal("-1"))).hasMessageContaining("0 or more");
        assertThatThrownBy(() -> w.setCash("RRSP", new BigDecimal("1.234"))).hasMessageContaining("2 decimals");
        assertThat(reader().accounts()).filteredOn(x -> x.name().equals("RRSP")).singleElement()
                .satisfies(x -> assertThat(x.cashBalance()).isEqualByComparingTo("1234.50"));
    }

    @Test
    void createsUpdatesAndDeletesATransaction() throws Exception {
        PortfolioWriter w = writer();
        int before = reader().activities().size();

        Activity a = w.create(buy("2026-03-02", "100", "60.25"));
        assertThat(a.id()).isNotBlank();
        assertThat(a.type()).isEqualTo("BUY");
        assertThat(a.symbol()).isEqualTo("FTS.TO");
        assertThat(a.account()).isEqualTo("RRSP");
        assertThat(a.date()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(a.quantity()).isEqualByComparingTo("100");
        assertThat(a.unitPrice()).isEqualByComparingTo("60.25");
        assertThat(a.fee()).isEqualByComparingTo("4.95");
        assertThat(a.currency()).isEqualTo("CAD");
        assertThat(a.comment()).isEqualTo("entered in the Cockpit");
        assertThat(reader().activities()).hasSize(before + 1).anyMatch(x -> x.id().equals(a.id()));

        Activity u = w.update(a.id(), buy("2026-03-03", "100", "61.10"));
        assertThat(u.id()).isEqualTo(a.id());
        assertThat(u.unitPrice()).isEqualByComparingTo("61.10");
        assertThat(u.date()).isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(reader().activities()).hasSize(before + 1);

        w.delete(a.id());
        assertThat(reader().activities()).hasSize(before).noneMatch(x -> a.id().equals(x.id()));
    }

    @Test
    void refusesInvalidInputAndWritesNothing() throws Exception {
        PortfolioWriter w = writer();
        int before = reader().activities().size();
        ActivityDraft bad = new ActivityDraft("swap", "TFSA", "<x>", LocalDate.of(2026, 12, 1), BigDecimal.ZERO,
                new BigDecimal("-1"), new BigDecimal("-2"), "EUR", null);
        assertThatThrownBy(() -> w.create(bad)).isInstanceOf(DataSourceException.class)
                .hasMessageContaining("Type must be").hasMessageContaining("Unknown account \"TFSA\"")
                .hasMessageContaining("Yahoo form").hasMessageContaining("in the future").hasMessageContaining("Shares must be more than 0")
                .hasMessageContaining("Price must be 0 or more").hasMessageContaining("Fee must be 0 or more")
                .hasMessageContaining("Currency must be CAD or USD");
        assertThat(reader().activities()).hasSize(before);
    }

    @Test
    void unknownIdsAreClearErrors() {
        assertThatThrownBy(() -> writer().update("9999999", buy("2026-03-02", "1", "1"))).isInstanceOf(DataSourceException.class);
        assertThatThrownBy(() -> writer().delete("9999999")).isInstanceOf(DataSourceException.class);
    }
}
