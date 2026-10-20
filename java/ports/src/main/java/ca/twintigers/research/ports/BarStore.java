package ca.twintigers.research.ports;

import ca.twintigers.research.domain.market.Bar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistent cache of daily bars, so each symbol is fetched from the provider at most once a day. */
public interface BarStore {

    List<Bar> load(String symbol, LocalDate from, LocalDate to);

    /** Stores the bars and records that {@code requestedFrom..requestedTo} was fetched (the first bar can be later: holidays). */
    void save(String symbol, String source, List<Bar> bars, LocalDate requestedFrom, LocalDate requestedTo, Instant fetchedAt);

    /** When the symbol was last fetched, and the range fetched then. */
    record FetchInfo(Instant fetchedAt, LocalDate from, LocalDate to, String source) {
    }

    Optional<FetchInfo> lastFetch(String symbol);
}
