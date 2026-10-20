package ca.twintigers.research.adapters.market;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Tries each provider in order; the first one that returns bars wins. Errors are collected for the message. */
public final class FallbackMarketData implements MarketData {

    private final List<MarketData> providers;
    private String lastSource;

    public FallbackMarketData(List<MarketData> providers) {
        this.providers = List.copyOf(providers);
        this.lastSource = providers.getFirst().source();
    }

    @Override
    public String source() {
        return lastSource;
    }

    @Override
    public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
        List<String> errors = new ArrayList<>();
        for (MarketData p : providers) {
            try {
                List<Bar> bars = p.dailyBars(symbol, from, to);
                if (!bars.isEmpty()) {
                    lastSource = p.source();
                    return bars;
                }
                errors.add(p.source() + ": no bars");
            } catch (DataSourceException e) {
                errors.add(p.source() + ": " + e.getMessage());
            }
        }
        throw new DataSourceException("No daily bars for " + symbol + ". " + String.join(" | ", errors));
    }
}
