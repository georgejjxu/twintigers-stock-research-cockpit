package ca.twintigers.research.adapters.market;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Candle;
import ca.twintigers.research.domain.market.Interval;
import ca.twintigers.research.ports.IntradayData;
import ca.twintigers.research.domain.market.CorporateAction;
import ca.twintigers.research.ports.CorporateActions;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.QuoteSource;
import ca.twintigers.research.ports.SymbolSearch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Daily bars from Yahoo Finance's chart API (free, no key, unofficial; TSX symbols end in .TO).
 * Prices are as traded (not adjusted for dividends); days with missing values are skipped.
 */
public final class YahooChartClient implements MarketData, QuoteSource, CorporateActions, SymbolSearch, IntradayData {

    private final Http http;
    private final String baseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final java.util.concurrent.ConcurrentHashMap<String, List<CorporateAction>> actionCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, CachedSeries> intradayCache = new java.util.concurrent.ConcurrentHashMap<>();

    public YahooChartClient(Http http) {
        this(http, "https://query1.finance.yahoo.com");
    }

    public YahooChartClient(Http http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public String source() {
        return "Yahoo Finance chart API (daily, end of day)";
    }

    @Override
    public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
        long p1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long p2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?period1=" + p1 + "&period2=" + p2 + "&interval=1d&includePrePost=false");
        Http.Response r = http.get(uri, Map.of("User-Agent", "Mozilla/5.0 (personal research tool)", "Accept", "application/json"));
        if (r.status() == 404) {
            throw new DataSourceException("Yahoo Finance does not know the symbol " + symbol
                    + " (TSX listings need the .TO suffix, e.g. ENB.TO).");
        }
        if (r.status() >= 400) {
            throw new DataSourceException("Yahoo Finance answered HTTP " + r.status() + " for " + symbol + ".");
        }
        return parse(json.readTree(r.body()), symbol);
    }

    /**
     * Splits and dividends from the chart API's events (dividend amounts are split-adjusted, like the closes).
     * Remembered for the day, so the Cockpit and the monitor ask Yahoo once per symbol per day.
     */
    @Override
    public List<CorporateAction> corporateActions(String symbol, LocalDate from, LocalDate to) throws DataSourceException {
        String key = symbol + "|" + from + "|" + to + "|" + LocalDate.now(ZoneOffset.UTC);
        List<CorporateAction> hit = actionCache.get(key);
        if (hit != null) {
            return hit;
        }
        long p1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long p2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?period1=" + p1 + "&period2=" + p2 + "&interval=1d&events=div%2Csplits");
        Http.Response r = http.get(uri, Map.of("User-Agent", "Mozilla/5.0 (personal research tool)", "Accept", "application/json"));
        if (r.status() >= 400) {
            throw new DataSourceException("Yahoo Finance answered HTTP " + r.status() + " for the dividends and splits of " + symbol + ".");
        }
        List<CorporateAction> out = parseActions(json.readTree(r.body()), symbol);
        if (actionCache.size() > 500) {
            actionCache.clear();
        }
        actionCache.put(key, out);
        return out;
    }

    public static List<CorporateAction> parseActions(JsonNode root, String symbol) {
        JsonNode res = root.path("chart").path("result").path(0);
        ZoneId zone;
        try {
            zone = ZoneId.of(res.path("meta").path("exchangeTimezoneName").asString("America/New_York"));
        } catch (RuntimeException e) {
            zone = ZoneId.of("America/New_York");
        }
        List<CorporateAction> out = new ArrayList<>();
        for (JsonNode d : res.path("events").path("dividends").values()) {
            if (d.path("amount").isNumber() && d.path("date").isNumber()) {
                out.add(CorporateAction.dividend(symbol, Instant.ofEpochSecond(d.path("date").asLong()).atZone(zone).toLocalDate(),
                        d.path("amount").asDouble()));
            }
        }
        for (JsonNode s : res.path("events").path("splits").values()) {
            if (s.path("numerator").isNumber() && s.path("denominator").isNumber() && s.path("denominator").asDouble() > 0) {
                out.add(CorporateAction.split(symbol, Instant.ofEpochSecond(s.path("date").asLong()).atZone(zone).toLocalDate(),
                        s.path("numerator").asDouble(), s.path("denominator").asDouble()));
            }
        }
        out.sort(java.util.Comparator.comparing(CorporateAction::date));
        return out;
    }

    /**
     * Intraday bars from the chart API (regular session, delayed about 15 minutes). Remembered for 60 seconds per
     * symbol and interval, so an open chart refreshing every minute costs one request.
     */
    @Override
    public IntradayData.Series intraday(String symbol, Interval interval, Instant from) throws DataSourceException {
        Instant now = Instant.now();
        String key = symbol + "|" + interval + "|" + from.getEpochSecond() / 3600;
        CachedSeries hit = intradayCache.get(key);
        if (hit != null && hit.at().isAfter(now.minusSeconds(60))) {
            return hit.series();
        }
        String yahooInterval = interval == Interval.H1 ? "60m" : interval.code();
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?period1=" + from.getEpochSecond() + "&period2=" + now.getEpochSecond() + "&interval=" + yahooInterval
                + "&includePrePost=false");
        Http.Response r = http.get(uri, Map.of("User-Agent", "Mozilla/5.0 (personal research tool)", "Accept", "application/json"));
        if (r.status() == 404) {
            throw new DataSourceException("Yahoo Finance does not know the symbol " + symbol + ".");
        }
        if (r.status() >= 400) {
            throw new DataSourceException("Yahoo Finance answered HTTP " + r.status() + " for " + interval.code() + " bars of " + symbol
                    + " (1-minute bars go back about 7 days, 5 to 30 minutes about 60 days).");
        }
        IntradayData.Series s = parseIntraday(json.readTree(r.body()), symbol, interval, now);
        if (intradayCache.size() > 200) {
            intradayCache.clear();
        }
        intradayCache.put(key, new CachedSeries(now, s));
        return s;
    }

    private record CachedSeries(Instant at, IntradayData.Series series) {
    }

    public static IntradayData.Series parseIntraday(JsonNode root, String symbol, Interval interval, Instant asOf) throws DataSourceException {
        JsonNode err = root.path("chart").path("error");
        if (!err.isMissingNode() && !err.isNull()) {
            throw new DataSourceException("Yahoo Finance error for " + symbol + ": " + err.path("description").asString("unknown"));
        }
        JsonNode res = root.path("chart").path("result").path(0);
        ZoneId zone;
        try {
            zone = ZoneId.of(res.path("meta").path("exchangeTimezoneName").asString("America/New_York"));
        } catch (RuntimeException e) {
            zone = ZoneId.of("America/New_York");
        }
        JsonNode ts = res.path("timestamp");
        JsonNode q = res.path("indicators").path("quote").path(0);
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            JsonNode o = q.path("open").path(i);
            JsonNode h = q.path("high").path(i);
            JsonNode l = q.path("low").path(i);
            JsonNode c = q.path("close").path(i);
            if (!o.isNumber() || !h.isNumber() || !l.isNumber() || !c.isNumber()) {
                continue;
            }
            double hi = Math.max(h.asDouble(), Math.max(o.asDouble(), c.asDouble()));
            double lo = Math.min(l.asDouble(), Math.min(o.asDouble(), c.asDouble()));
            JsonNode v = q.path("volume").path(i);
            out.add(new Candle(Instant.ofEpochSecond(ts.path(i).asLong()), o.asDouble(), hi, lo, c.asDouble(), v.isNumber() ? v.asLong() : 0L));
        }
        return new IntradayData.Series(symbol, interval, zone, out, "Yahoo Finance chart API (intraday, delayed about 15 minutes)", 15, asOf);
    }

    /** Yahoo's search (symbol or company name), stocks and ETFs only. */
    @Override
    public List<SymbolSearch.Match> search(String query, int max) throws DataSourceException {
        String q = query == null ? "" : query.strip();
        if (q.length() < 1 || q.length() > 40) {
            return List.of();
        }
        URI uri = URI.create(baseUrl + "/v1/finance/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&quotesCount=" + Math.max(1, Math.min(max * 2, 20)) + "&newsCount=0&listsCount=0");
        Http.Response r = http.get(uri, Map.of("User-Agent", "Mozilla/5.0 (personal research tool)", "Accept", "application/json"));
        if (r.status() >= 400) {
            throw new DataSourceException("Yahoo Finance search answered HTTP " + r.status() + ".");
        }
        return parseSearch(json.readTree(r.body()), max);
    }

    public static List<SymbolSearch.Match> parseSearch(JsonNode root, int max) {
        List<SymbolSearch.Match> out = new ArrayList<>();
        for (JsonNode q : root.path("quotes").values()) {
            String type = q.path("quoteType").asString("");
            String symbol = q.path("symbol").asString("");
            if ((type.equals("EQUITY") || type.equals("ETF")) && symbol.matches("[A-Za-z0-9.=^-]{1,20}") && out.size() < max) {
                String name = q.path("longname").asString(q.path("shortname").asString(symbol));
                out.add(new SymbolSearch.Match(symbol.toUpperCase(java.util.Locale.ROOT), name, q.path("exchDisp").asString(""),
                        type.equals("ETF") ? "ETF" : "Stock", q.path("sector").isString() ? q.path("sector").asString() : null));
            }
        }
        return out;
    }

    /** Latest (delayed) trade price from the chart API's metadata. */
    @Override
    public QuoteSource.Quote latest(String symbol) throws DataSourceException {
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?range=1d&interval=1d");
        Http.Response r = http.get(uri, Map.of("User-Agent", "Mozilla/5.0 (personal research tool)", "Accept", "application/json"));
        if (r.status() >= 400) {
            throw new DataSourceException("Yahoo Finance answered HTTP " + r.status() + " for a quote of " + symbol + ".");
        }
        JsonNode meta = json.readTree(r.body()).path("chart").path("result").path(0).path("meta");
        if (!meta.path("regularMarketPrice").isNumber()) {
            throw new DataSourceException("Yahoo Finance returned no price for " + symbol + ".");
        }
        return new QuoteSource.Quote(symbol, meta.path("regularMarketPrice").asDouble(),
                Instant.ofEpochSecond(meta.path("regularMarketTime").asLong()), "Yahoo Finance");
    }

    static List<Bar> parse(JsonNode root, String symbol) throws DataSourceException {
        JsonNode err = root.path("chart").path("error");
        if (!err.isMissingNode() && !err.isNull()) {
            throw new DataSourceException("Yahoo Finance error for " + symbol + ": " + err.path("description").asString("unknown"));
        }
        JsonNode res = root.path("chart").path("result").path(0);
        JsonNode ts = res.path("timestamp");
        JsonNode q = res.path("indicators").path("quote").path(0);
        ZoneId zone;
        try {
            zone = ZoneId.of(res.path("meta").path("exchangeTimezoneName").asString("America/New_York"));
        } catch (RuntimeException e) {
            zone = ZoneId.of("America/New_York");
        }
        TreeMap<LocalDate, Bar> byDate = new TreeMap<>();
        for (int i = 0; i < ts.size(); i++) {
            JsonNode o = q.path("open").path(i);
            JsonNode h = q.path("high").path(i);
            JsonNode l = q.path("low").path(i);
            JsonNode c = q.path("close").path(i);
            JsonNode v = q.path("volume").path(i);
            if (!o.isNumber() || !h.isNumber() || !l.isNumber() || !c.isNumber()) {
                continue;
            }
            LocalDate d = Instant.ofEpochSecond(ts.path(i).asLong()).atZone(zone).toLocalDate();
            double hi = Math.max(h.asDouble(), Math.max(o.asDouble(), c.asDouble()));
            double lo = Math.min(l.asDouble(), Math.min(o.asDouble(), c.asDouble()));
            byDate.put(d, new Bar(d, o.asDouble(), hi, lo, c.asDouble(), v.isNumber() ? v.asLong() : 0L));
        }
        return new ArrayList<>(byDate.values());
    }
}
