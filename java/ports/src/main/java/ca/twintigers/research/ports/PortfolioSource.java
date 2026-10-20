package ca.twintigers.research.ports;

import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.Portfolio;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the user's holdings come from (read-only). Today: Ghostfolio, or a local source (portfolio.toml + market
 * prices). Tomorrow: a broker API. Everything outside the adapters (UI, MCP tools, the monitor) depends on this
 * interface only, so a source can be swapped by configuration ({@code PORTFOLIO_SOURCE}); the behaviour every
 * implementation must have is fixed by the shared contract tests (adapters: PortfolioSourceContract).
 */
public interface PortfolioSource {

    /** Optional parts of the contract; a source declares what it can answer. */
    enum Capability {
        /** {@link #accounts()} lists the accounts with their cash. */
        ACCOUNTS,
        /** {@link #activities()} returns the transaction history (and so dividends). */
        ACTIVITIES,
        /** {@link #performance(String)} computes returns (needs cost basis and history). */
        PERFORMANCE
    }

    /** Short id, e.g. "ghostfolio" or "local", shown in the UI and reports. */
    String name();

    Set<Capability> capabilities();

    /** Positions with market values in the base currency, and total cash. */
    Portfolio portfolio() throws DataSourceException;

    /** Accounts with their cash balances; empty if the source lacks {@link Capability#ACCOUNTS}. */
    List<Account> accounts() throws DataSourceException;

    /** Transactions, newest first; empty if the source lacks {@link Capability#ACTIVITIES}. */
    List<Activity> activities() throws DataSourceException;

    /** Dividends received, newest first (a subset of the activities). */
    default List<Activity> dividends() throws DataSourceException {
        return activities().stream().filter(Activity::isDividend).toList();
    }

    /** Dated cash balances per account, oldest first (empty when the source keeps only the current balance). */
    default List<ca.twintigers.research.domain.portfolio.CashBalance> cashHistory() throws DataSourceException {
        return List.of();
    }

    /** Sector, country and asset class per symbol, as far as the source knows them (empty by default). */
    default java.util.Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> profiles() throws DataSourceException {
        return java.util.Map.of();
    }

    /** Return over a range; empty if the source lacks {@link Capability#PERFORMANCE}. */
    Optional<Performance> performance(String range) throws DataSourceException;
}
