package ca.twintigers.research.adapters.market;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.domain.market.Sentiment;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.SentimentSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CNN's Fear &amp; Greed Index for US stocks (unofficial JSON behind cnn.com/markets/fear-and-greed; free, no key) and the
 * Crypto Fear &amp; Greed Index (alternative.me, free). Read at most every 30 minutes; a failed read keeps the last one.
 */
public final class FearGreedClient implements SentimentSource {

    static final Map<String, String> COMPONENTS = new LinkedHashMap<>();

    static {
        COMPONENTS.put("market_momentum_sp500", "Market momentum (S&P 500 vs its 125-day average)");
        COMPONENTS.put("stock_price_strength", "Stock price strength (52-week highs vs lows)");
        COMPONENTS.put("stock_price_breadth", "Stock price breadth (rising vs falling volume)");
        COMPONENTS.put("put_call_options", "Put and call options (put/call ratio)");
        COMPONENTS.put("market_volatility_vix", "Market volatility (VIX vs its 50-day average)");
        COMPONENTS.put("safe_haven_demand", "Safe-haven demand (stocks vs bonds, 20 days)");
        COMPONENTS.put("junk_bond_demand", "Junk-bond demand (junk vs investment-grade yields)");
    }

    private final Http http;
    private final String cnnUrl;
    private final String cryptoUrl;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private volatile List<Sentiment> last = List.of();
    private volatile Instant lastAt = Instant.EPOCH;

    public FearGreedClient(Http http, Clock clock) {
        this(http, "https://production.dataviz.cnn.io/index/fearandgreed/graphdata", "https://api.alternative.me/fng/?limit=365", clock);
    }

    public FearGreedClient(Http http, String cnnUrl, String cryptoUrl, Clock clock) {
        this.http = http;
        this.cnnUrl = cnnUrl;
        this.cryptoUrl = cryptoUrl;
        this.clock = clock;
    }

    @Override
    public synchronized List<Sentiment> sentiment() throws DataSourceException {
        if (!last.isEmpty() && lastAt.isAfter(clock.instant().minusSeconds(1800))) {
            return last;
        }
        List<Sentiment> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            Http.Response r = http.get(URI.create(cnnUrl), Map.of("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
                    "Accept", "application/json", "Referer", "https://www.cnn.com/"));
            if (r.status() >= 400) {
                throw new DataSourceException("CNN answered HTTP " + r.status() + ".");
            }
            out.add(parseCnn(json.readTree(r.body())));
        } catch (DataSourceException | RuntimeException e) {
            errors.add("Fear & Greed (stocks): " + e.getMessage());
        }
        try {
            Http.Response r = http.get(URI.create(cryptoUrl), Map.of("Accept", "application/json"));
            if (r.status() >= 400) {
                throw new DataSourceException("alternative.me answered HTTP " + r.status() + ".");
            }
            out.add(parseCrypto(json.readTree(r.body())));
        } catch (DataSourceException | RuntimeException e) {
            errors.add("Fear & Greed (crypto): " + e.getMessage());
        }
        if (out.isEmpty()) {
            if (!last.isEmpty()) {
                return last;
            }
            throw new DataSourceException(String.join(" ", errors));
        }
        last = List.copyOf(out);
        lastAt = clock.instant();
        return last;
    }

    public static Sentiment parseCnn(JsonNode root) {
        JsonNode now = root.path("fear_and_greed");
        double score = now.path("score").asDouble();
        List<Sentiment.Reading> previous = new ArrayList<>();
        for (String[] k : new String[][] {{"previous_close", "Previous close"}, {"previous_1_week", "1 week ago"},
                {"previous_1_month", "1 month ago"}, {"previous_1_year", "1 year ago"}}) {
            if (now.path(k[0]).isNumber()) {
                double v = now.path(k[0]).asDouble();
                previous.add(new Sentiment.Reading(k[1], round(v), Sentiment.band(v)));
            }
        }
        List<Sentiment.Reading> parts = new ArrayList<>();
        COMPONENTS.forEach((key, label) -> {
            JsonNode c = root.path(key);
            if (c.path("score").isNumber()) {
                parts.add(new Sentiment.Reading(label, round(c.path("score").asDouble()), c.path("rating").asString("")));
            }
        });
        TreeMap<LocalDate, Double> hist = new TreeMap<>();
        for (JsonNode p : root.path("fear_and_greed_historical").path("data").values()) {
            if (p.path("x").isNumber() && p.path("y").isNumber()) {
                hist.put(Instant.ofEpochMilli(p.path("x").asLong()).atZone(ZoneOffset.UTC).toLocalDate(), round(p.path("y").asDouble()));
            }
        }
        Instant asOf;
        try {
            asOf = OffsetDateTime.parse(now.path("timestamp").asString("")).toInstant();
        } catch (RuntimeException e) {
            asOf = Instant.now();
        }
        List<Sentiment.Point> points = new ArrayList<>();
        hist.forEach((d, v) -> points.add(new Sentiment.Point(d, v)));
        return new Sentiment("Fear & Greed Index (US stocks)", "CNN Business", round(score), now.path("rating").asString(Sentiment.band(score)),
                asOf, previous, parts, points);
    }

    public static Sentiment parseCrypto(JsonNode root) {
        List<JsonNode> data = new ArrayList<>(root.path("data").values());
        if (data.isEmpty()) {
            throw new IllegalStateException("no data");
        }
        TreeMap<LocalDate, Double> hist = new TreeMap<>();
        for (JsonNode p : data) {
            hist.put(Instant.ofEpochSecond(Long.parseLong(p.path("timestamp").asString("0"))).atZone(ZoneOffset.UTC).toLocalDate(),
                    Double.parseDouble(p.path("value").asString("0")));
        }
        JsonNode now = data.getFirst();
        double score = Double.parseDouble(now.path("value").asString("0"));
        List<Sentiment.Reading> previous = new ArrayList<>();
        LocalDate today = hist.lastKey();
        for (Object[] k : new Object[][] {{1, "Yesterday"}, {7, "1 week ago"}, {30, "1 month ago"}}) {
            Map.Entry<LocalDate, Double> e = hist.floorEntry(today.minusDays((Integer) k[0]));
            if (e != null) {
                previous.add(new Sentiment.Reading((String) k[1], e.getValue(), Sentiment.band(e.getValue())));
            }
        }
        List<Sentiment.Point> points = new ArrayList<>();
        hist.forEach((d, v) -> points.add(new Sentiment.Point(d, v)));
        return new Sentiment("Fear & Greed Index (crypto)", "alternative.me", score,
                now.path("value_classification").asString(Sentiment.band(score)).toLowerCase(java.util.Locale.ROOT),
                Instant.ofEpochSecond(Long.parseLong(now.path("timestamp").asString("0"))), previous, List.of(), points);
    }

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
