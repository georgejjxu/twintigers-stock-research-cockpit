package ca.twintigers.research.ports;

import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;

import java.util.List;

/**
 * Records the user's transactions in the holdings source: the only port that changes the portfolio data. Each
 * implementation validates the draft ({@link ActivityDraft#problems}) and refuses invalid input with a
 * {@link DataSourceException} naming every problem. The behaviour is fixed by the shared contract tests
 * (adapters: PortfolioWriterContract).
 */
public interface PortfolioWriter {

    /** Short id of the source written to ("ghostfolio", "local"). */
    String name();

    /** Whether writing is switched on (Ghostfolio needs GHOSTFOLIO_WRITES=enabled). */
    boolean enabled();

    /** Account names a transaction may be recorded in. */
    List<String> accountNames() throws DataSourceException;

    Activity create(ActivityDraft draft) throws DataSourceException;

    Activity update(String id, ActivityDraft draft) throws DataSourceException;

    void delete(String id) throws DataSourceException;

    /**
     * Sets an account's cash balance (in the account's currency), as the user would in the tracker. Refuses an unknown
     * account and a negative or missing amount.
     */
    ca.twintigers.research.domain.portfolio.Account setCash(String account, java.math.BigDecimal cash) throws DataSourceException;

    /** Adds an account (name, 3-letter currency, starting cash). Sources that cannot add accounts say so. */
    default ca.twintigers.research.domain.portfolio.Account addAccount(String name, String currency, java.math.BigDecimal cash)
            throws DataSourceException {
        throw new DataSourceException("Accounts cannot be added in " + name() + "; add them there.");
    }

    /** Shared validation for {@link #setCash}. */
    static void checkCash(String account, java.math.BigDecimal cash, List<String> accounts) throws DataSourceException {
        if (account == null || !accounts.contains(account)) {
            throw new DataSourceException("Unknown account \"" + account + "\". Accounts: " + String.join(", ", accounts) + ".");
        }
        if (cash == null || cash.signum() < 0) {
            throw new DataSourceException("Cash must be 0 or more.");
        }
        if (cash.scale() > 2 && cash.stripTrailingZeros().scale() > 2) {
            throw new DataSourceException("Cash has at most 2 decimals.");
        }
    }
}
