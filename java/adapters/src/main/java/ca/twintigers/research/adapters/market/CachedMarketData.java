package ca.twintigers.research.adapters.market;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.ports.BarStore;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Serves bars from the {@link BarStore} when the symbol was fetched since the last 16:15 Eastern cutoff and the
 * stored range covers the request; otherwise fetches from the provider (the whole span, from the earliest date
 * ever requested) and stores the result.
 */
public final class CachedMarketData implements MarketData {

    private final MarketData provider;
    private final BarStore store;
    private final Clock clock;

    public CachedMarketData(MarketData provider, BarStore store, Clock clock) {
        this.provider = provider;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String source() {
        return provider.source() + ", cached locally";
    }

    @Override
    public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
        LocalDate today = LocalDate.now(clock);
        Optional<BarStore.FetchInfo> info = store.lastFetch(symbol);
        // Bars are final after 16:15 Eastern. A fetch made since the most recent such cutoff is current; an older one
        // (e.g. made during the session, with a partial bar) is refreshed. So at most a few provider calls per day.
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(clock.withZone(java.time.ZoneId.of("America/Toronto")));
        java.time.ZonedDateTime cutoff = now.toLocalDate().atTime(16, 15).atZone(now.getZone());
        if (now.isBefore(cutoff)) {
            cutoff = cutoff.minusDays(1);
        }
        boolean fresh = info.isPresent()
                && !info.get().fetchedAt().isBefore(cutoff.toInstant())
                && !info.get().from().isAfter(from);
        if (!fresh) {
            LocalDate start = info.map(i -> i.from().isBefore(from) ? i.from() : from).orElse(from);
            List<Bar> bars = provider.dailyBars(symbol, start, today);
            store.save(symbol, provider.source(), bars, start, today, clock.instant());
        }
        return store.load(symbol, from, to);
    }
}
