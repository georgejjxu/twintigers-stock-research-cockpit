package ca.twintigers.research.domain.portfolio;

import java.time.Instant;

/** A symbol the user watches (owned or not). Kept in our own store, independent of the portfolio source. */
public record WatchItem(String symbol, String note, Instant addedAt) {
}
