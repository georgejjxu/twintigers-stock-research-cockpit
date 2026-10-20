package ca.twintigers.research.application;

import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.LedgerBook;
import ca.twintigers.research.ports.PortfolioSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mirrors one holdings source (Ghostfolio) into the local ledger: accounts with their cash, instrument names, and
 * every buy, sell and dividend, keyed by the source's id. Running it again updates changed transactions and removes
 * ones deleted at the source; transactions entered locally (no source id) are never touched. Reads the source only.
 */
public final class LedgerSync {

    /** What a sync changed. {@code skipped} lists transactions the ledger cannot hold, with the reason. */
    public record Result(int accounts, int accountsRemoved, int added, int updated, int unchanged, int removed, List<String> skipped) {
        public String summary() {
            return accounts + " accounts" + (accountsRemoved > 0 ? " (" + accountsRemoved + " removed)" : "") + "; transactions: "
                    + added + " added, " + updated + " updated, " + unchanged + " unchanged, " + removed + " removed"
                    + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped");
        }
    }

    public Result sync(PortfolioSource from, LedgerBook to) throws DataSourceException {
        List<Account> accounts = from.accounts();
        Set<String> names = new HashSet<>();
        for (Account a : accounts) {
            to.putAccount(a.name(), a.currency(), a.cashBalance());
            names.add(a.name());
        }
        int accountsRemoved = 0;
        for (Account a : to.ledgerAccounts()) {
            if (!names.contains(a.name())) {
                to.removeAccount(a.name());
                accountsRemoved++;
            }
        }
        Map<String, List<ca.twintigers.research.domain.portfolio.CashBalance>> history = new java.util.LinkedHashMap<>();
        for (var b : from.cashHistory()) {
            history.computeIfAbsent(b.account(), k -> new ArrayList<>()).add(b);
        }
        for (String name : names) {
            if (history.containsKey(name)) {
                to.putCashHistory(name, history.get(name));
            }
        }
        try {
            for (var profile : from.profiles().values()) {
                to.putProfile(profile);
            }
        } catch (DataSourceException e) {
            // profiles are a nicety too
        }
        try {
            for (Portfolio.Position p : from.portfolio().positions()) {
                to.putInstrument(p.symbol(), p.name(), p.currency());
            }
        } catch (DataSourceException e) {
            // names are a nicety; the ledger falls back to symbols
        }

        Map<String, Activity> mirrored = to.mirrored();
        Set<String> seen = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        for (Activity a : from.activities()) {
            if (a.id() == null) {
                skipped.add(describe(a) + ": no id at the source");
                continue;
            }
            ActivityDraft d = new ActivityDraft(a.type(), a.account(), a.symbol(), a.date(), a.quantity(), a.unitPrice(), a.fee(),
                    a.currency(), a.comment()).normalized();
            if (!ActivityDraft.TYPES.contains(d.type())) {
                skipped.add(describe(a) + ": type " + a.type() + " is not kept in the ledger");
                continue;
            }
            seen.add(a.id());
            Activity mine = mirrored.get(a.id());
            if (mine != null && same(mine, d)) {
                unchanged++;
                continue;
            }
            try {
                to.putMirrored(a.id(), d);
                if (mine == null) {
                    added++;
                } else {
                    updated++;
                }
            } catch (DataSourceException e) {
                seen.remove(a.id());
                skipped.add(describe(a) + ": " + e.getMessage());
            }
        }
        int removed = 0;
        for (String id : mirrored.keySet()) {
            if (!seen.contains(id) && skipped.stream().noneMatch(s -> s.contains(id))) {
                to.removeMirrored(id);
                removed++;
            }
        }
        return new Result(accounts.size(), accountsRemoved, added, updated, unchanged, removed, List.copyOf(skipped));
    }

    static boolean same(Activity a, ActivityDraft d) {
        return Objects.equals(a.date(), d.date()) && Objects.equals(a.type(), d.type()) && Objects.equals(a.account(), d.account())
                && Objects.equals(a.symbol(), d.symbol()) && a.quantity().compareTo(d.quantity()) == 0
                && a.unitPrice().compareTo(d.unitPrice()) == 0 && a.fee().compareTo(d.fee()) == 0
                && Objects.equals(a.currency(), d.currency()) && Objects.equals(a.comment(), d.comment());
    }

    static String describe(Activity a) {
        return a.type() + " " + (a.quantity() == null ? "" : a.quantity().stripTrailingZeros().toPlainString()) + " " + a.symbol()
                + " on " + a.date() + " in " + a.account() + " (id " + a.id() + ")";
    }
}
