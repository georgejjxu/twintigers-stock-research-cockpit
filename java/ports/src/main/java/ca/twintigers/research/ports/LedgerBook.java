package ca.twintigers.research.ports;

import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The local ledger as a mirror target: accounts, instrument names and transactions copied from another holdings
 * source (Ghostfolio, until the switch to the local source). Mirrored rows carry the other source's id
 * ({@code externalId}); rows entered locally have none and are never touched by a sync.
 */
public interface LedgerBook {

    /** Accounts kept in the ledger (empty until the first sync; the local source then falls back to portfolio.toml). */
    List<Account> ledgerAccounts() throws DataSourceException;

    void putAccount(String name, String currency, BigDecimal cash) throws DataSourceException;

    void removeAccount(String name) throws DataSourceException;

    void putInstrument(String symbol, String name, String currency) throws DataSourceException;

    /** Stores the sector, country and asset-class profile of a symbol (from the mirrored source). */
    void putProfile(ca.twintigers.research.domain.portfolio.InstrumentProfile profile) throws DataSourceException;

    /** Replaces the account's dated cash balances (the source's history; see CashBalance). */
    void putCashHistory(String account, List<ca.twintigers.research.domain.portfolio.CashBalance> points) throws DataSourceException;

    /** Mirrored transactions by their source's id. */
    Map<String, Activity> mirrored() throws DataSourceException;

    /** Inserts or updates the row mirroring {@code externalId}. */
    Activity putMirrored(String externalId, ActivityDraft draft) throws DataSourceException;

    void removeMirrored(String externalId) throws DataSourceException;
}
