package ca.twintigers.research.adapters.market;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Daily bars from Alpha Vantage (TIME_SERIES_DAILY, full history). Needs ALPHAVANTAGE_API_KEY; the free tier
 * allows 25 requests a day, so this is only the fallback. TSX symbols map from ENB.TO to ENB.TRT.
 */
public final class AlphaVantageClient implements MarketData {

    private final Http http;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper json = new ObjectMapper();

    public AlphaVantageClient(Http http, String apiKey) {
        this(http, apiKey, "https://www.alphavantage.co");
    }

    public AlphaVantageClient(Http http, String apiKey, String baseUrl) {
        this.http = http;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String source() {
        return "Alpha Vantage TIME_SERIES_DAILY";
    }

    static String toAlphaVantage(String symbol) {
        return symbol.endsWith(".TO") ? symbol.substring(0, symbol.length() - 3) + ".TRT" : symbol;
    }

    @Override
    public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException("Alpha Vantage fallback not configured (no ALPHAVANTAGE_API_KEY).");
        }
        URI uri = URI.create(baseUrl + "/query?function=TIME_SERIES_DAILY&outputsize=full&symbol="
                + URLEncoder.encode(toAlphaVantage(symbol), StandardCharsets.UTF_8) + "&apikey="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        Http.Response r = http.get(uri, Map.of());
        if (r.status() >= 400) {
            throw new DataSourceException("Alpha Vantage answered HTTP " + r.status() + " for " + symbol + ".");
        }
        JsonNode root = json.readTree(r.body());
        JsonNode series = root.path("Time Series (Daily)");
        if (series.isMissingNode()) {
            String msg = root.path("Note").asString(root.path("Information").asString(root.path("Error Message").asString("no data")));
            throw new DataSourceException("Alpha Vantage returned no daily series for " + symbol + ": "
                    + msg.replace(apiKey, "***"));
        }
        List<Bar> bars = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : series.properties()) {
            LocalDate d = LocalDate.parse(e.getKey());
            if (d.isBefore(from) || d.isAfter(to)) {
                continue;
            }
            JsonNode b = e.getValue();
            bars.add(new Bar(d, num(b, "1. open"), num(b, "2. high"), num(b, "3. low"), num(b, "4. close"),
                    (long) num(b, "5. volume")));
        }
        bars.sort(java.util.Comparator.comparing(Bar::date));
        return bars;
    }

    private static double num(JsonNode b, String field) {
        return Double.parseDouble(b.path(field).asString("0"));
    }
}
