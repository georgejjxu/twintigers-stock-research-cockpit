package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Indicators;
import ca.twintigers.research.domain.market.Sentiment;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.SentimentSource;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Markets page: sentiment (Fear &amp; Greed) and benchmark indices, each with its last daily close, the change on
 * the day, year to date and over a year, the distance from its 52-week high, and where it stands against its 50- and
 * 200-day averages (what Ghostfolio's Markets view shows).
 */
public final class MarketService {

    /** Default benchmarks (Yahoo symbols); override with market.benchmarks in rules.properties ("SYMBOL=Name;..."). */
    public static final Map<String, String> DEFAULT_BENCHMARKS = new LinkedHashMap<>();

    static {
        DEFAULT_BENCHMARKS.put("^GSPTSE", "S&P/TSX Composite");
        DEFAULT_BENCHMARKS.put("^GSPC", "S&P 500");
        DEFAULT_BENCHMARKS.put("^IXIC", "Nasdaq Composite");
        DEFAULT_BENCHMARKS.put("^DJI", "Dow Jones Industrial Average");
        DEFAULT_BENCHMARKS.put("^VIX", "VIX (expected S&P 500 volatility)");
        DEFAULT_BENCHMARKS.put("^TNX", "US 10-year Treasury yield (%)");
        DEFAULT_BENCHMARKS.put("USDCAD=X", "US dollar in Canadian dollars");
        DEFAULT_BENCHMARKS.put("GC=F", "Gold (US$/oz)");
        DEFAULT_BENCHMARKS.put("CL=F", "WTI crude oil (US$/bbl)");
        DEFAULT_BENCHMARKS.put("BTC-USD", "Bitcoin (US$)");
    }

    public record Benchmark(String symbol, String name, LocalDate date, Double close, Double dayPct, Double ytdPct, Double yearPct,
                            Double fromHighPct, Double sma50, Double sma200, String error) {
        public Boolean above50() {
            return close == null || sma50 == null ? null : close > sma50;
        }

        public Boolean above200() {
            return close == null || sma200 == null ? null : close > sma200;
        }
    }

    public record View(List<Sentiment> sentiment, String sentimentError, List<Benchmark> benchmarks, String source) {
    }

    private final MarketData market;
    private final SentimentSource sentiment;
    private final Map<String, String> benchmarks;
    private final Clock clock;

    public MarketService(MarketData market, SentimentSource sentiment, Map<String, String> benchmarks, Clock clock) {
        this.market = market;
        this.sentiment = sentiment;
        this.benchmarks = benchmarks.isEmpty() ? DEFAULT_BENCHMARKS : benchmarks;
        this.clock = clock;
    }

    /** "^GSPTSE=S&P/TSX Composite;^GSPC=S&P 500" → map; blank → defaults. */
    public static Map<String, String> parse(String config) {
        Map<String, String> out = new LinkedHashMap<>();
        if (config == null || config.isBlank()) {
            return out;
        }
        for (String part : config.split(";")) {
            String[] kv = part.split("=", 2);
            if (!kv[0].isBlank()) {
                out.put(kv[0].strip(), kv.length > 1 ? kv[1].strip() : kv[0].strip());
            }
        }
        return out;
    }

    public View view() {
        List<Sentiment> s = List.of();
        String err = null;
        try {
            s = sentiment.sentiment();
        } catch (DataSourceException | RuntimeException e) {
            err = e.getMessage();
        }
        List<Benchmark> rows = new ArrayList<>();
        benchmarks.forEach((symbol, name) -> rows.add(benchmark(symbol, name)));
        return new View(s, err, rows, market.source());
    }

    Benchmark benchmark(String symbol, String name) {
        LocalDate today = LocalDate.now(clock);
        try {
            List<Bar> bars = market.dailyBars(symbol, today.minusDays(420), today);
            if (bars.size() < 2) {
                return new Benchmark(symbol, name, null, null, null, null, null, null, null, null, "No prices.");
            }
            Bar last = bars.getLast();
            double close = last.close();
            double[] closes = bars.stream().mapToDouble(Bar::close).toArray();
            double[] s50 = Indicators.sma(closes, 50);
            double[] s200 = Indicators.sma(closes, 200);
            double high = bars.stream().filter(b -> !b.date().isBefore(last.date().minusYears(1))).mapToDouble(Bar::high).max().orElse(close);
            Double ytd = baseOn(bars, LocalDate.of(last.date().getYear(), 1, 1), close);
            Double year = baseOn(bars, last.date().minusYears(1).plusDays(1), close);
            return new Benchmark(symbol, name, last.date(), close, pct(close, bars.get(bars.size() - 2).close()), ytd, year,
                    pct(close, high), nan(s50[s50.length - 1]), nan(s200[s200.length - 1]), null);
        } catch (DataSourceException | RuntimeException e) {
            return new Benchmark(symbol, name, null, null, null, null, null, null, null, null, e.getMessage());
        }
    }

    /** Change from the last close before {@code start} to {@code close}, in %. */
    static Double baseOn(List<Bar> bars, LocalDate start, double close) {
        Double base = null;
        for (Bar b : bars) {
            if (b.date().isBefore(start)) {
                base = b.close();
            }
        }
        return base == null ? null : pct(close, base);
    }

    static Double pct(double now, double base) {
        return base == 0 ? null : Math.round((now / base - 1) * 10000) / 100.0;
    }

    static Double nan(double v) {
        return Double.isNaN(v) ? null : Math.round(v * 100) / 100.0;
    }
}
