package ca.twintigers.research.ports;

import ca.twintigers.research.domain.portfolio.WatchItem;

import java.util.List;

/** The user's watch list, kept in our own database so it survives replacing the portfolio source. */
public interface WatchlistStore {

    List<WatchItem> watchlist();

    /** Adds or updates the note. Symbols are stored upper-case (Yahoo form, ENB.TO). */
    WatchItem watch(String symbol, String note);

    boolean unwatch(String symbol);
}
