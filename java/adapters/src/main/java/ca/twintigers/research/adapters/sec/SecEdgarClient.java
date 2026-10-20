package ca.twintigers.research.adapters.sec;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.domain.fundamentals.CompanyFacts;
import ca.twintigers.research.domain.provenance.Fact;
import ca.twintigers.research.ports.CompanyDirectory;
import ca.twintigers.research.ports.CompanyFactsSource;
import ca.twintigers.research.ports.DataSourceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SEC EDGAR: ticker list, company profile (submissions, for the SIC industry code) and XBRL companyfacts.
 *
 * <p>Responses are cached on disk (RESEARCH_CACHE_DIR)
 * ({@code company_tickers.json}, {@code companyfacts/CIK##########.json}), so both implementations read
 * identical inputs. SEC requires a User-Agent naming a person and an email, and allows about 10 requests a
 * second; this client waits 120 ms between requests.
 */
public final class SecEdgarClient implements CompanyDirectory, CompanyFactsSource {

    static final String TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    static final String FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK%010d.json";
    static final String SUBMISSIONS_URL = "https://data.sec.gov/submissions/CIK%010d.json";
    static final Duration FACTS_MAX_AGE = Duration.ofHours(12);
    static final Duration TICKERS_MAX_AGE = Duration.ofDays(7);

    private final Http http;
    private final ObjectMapper json = new ObjectMapper();
    private final String userAgent;
    private final Path cacheDir;
    private final boolean offline;
    private final Clock clock;
    private final String tickersUrl;
    private final String dataBaseUrl;
    private long lastRequestNanos;

    public SecEdgarClient(Http http, String userAgent, Path cacheDir, boolean offline, Clock clock) {
        this(http, userAgent, cacheDir, offline, clock, TICKERS_URL, "https://data.sec.gov");
    }

    /** For tests: base URLs pointing at a stub server. */
    public SecEdgarClient(Http http, String userAgent, Path cacheDir, boolean offline, Clock clock, String tickersUrl,
                          String dataBaseUrl) {
        this.http = http;
        this.userAgent = userAgent;
        this.cacheDir = cacheDir;
        this.offline = offline;
        this.clock = clock;
        this.tickersUrl = tickersUrl;
        this.dataBaseUrl = dataBaseUrl;
    }

    private JsonNode getJson(String url, String cacheName, Duration maxAge) throws DataSourceException {
        Path path = cacheDir.resolve(cacheName);
        try {
            if (Files.exists(path) && (offline || Duration.between(Files.getLastModifiedTime(path).toInstant(),
                    clock.instant()).compareTo(maxAge) < 0)) {
                return json.readTree(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new DataSourceException("Cannot read cached " + path + ": " + e.getMessage(), e);
        }
        if (offline) {
            throw new DataSourceException("Offline and no cached copy at " + path + ".");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new DataSourceException("Set SEC_USER_AGENT to 'Your Name your@email' (required by SEC), e.g. in secrets.env.");
        }
        throttle();
        Http.Response r = http.get(URI.create(url), Map.of("User-Agent", userAgent));
        if (r.status() == 404) {
            throw new DataSourceException("Not found on EDGAR: " + url);
        }
        if (r.status() == 403) {
            throw new DataSourceException("SEC refused the request (HTTP 403). SEC requires SEC_USER_AGENT to name you and give "
                    + "an email address, e.g. 'Jane Doe jane@example.com'.");
        }
        if (r.status() >= 400) {
            throw new DataSourceException("SEC EDGAR answered HTTP " + r.status() + " for " + url);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, r.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataSourceException("Cannot write cache " + path + ": " + e.getMessage(), e);
        }
        return json.readTree(r.body());
    }

    private synchronized void throttle() {
        long wait = 120_000_000L - (System.nanoTime() - lastRequestNanos);
        if (wait > 0) {
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestNanos = System.nanoTime();
    }

    @Override
    public Optional<CompanyRef> lookup(String tickerOrCik) throws DataSourceException {
        String q = tickerOrCik.strip().toUpperCase(Locale.ROOT);
        long cik;
        String ticker = null;
        String name = null;
        if (q.chars().allMatch(Character::isDigit) && !q.isEmpty()) {
            cik = Long.parseLong(q);
        } else {
            JsonNode data = getJson(tickersUrl, "company_tickers.json", TICKERS_MAX_AGE);
            JsonNode hit = null;
            for (JsonNode row : data.values()) {
                if (row.path("ticker").asString("").equalsIgnoreCase(q)) {
                    hit = row;
                    break;
                }
            }
            if (hit == null) {
                return Optional.empty();
            }
            cik = hit.path("cik_str").asLong();
            ticker = hit.path("ticker").asString();
            name = hit.path("title").asString();
        }
        Integer sic = null;
        String sicDescription = null;
        try {
            JsonNode sub = getJson(dataBaseUrl + String.format("/submissions/CIK%010d.json", cik),
                    String.format("submissions/CIK%010d.json", cik), TICKERS_MAX_AGE);
            String s = sub.path("sic").asString("");
            sic = s.isBlank() ? null : Integer.valueOf(s.strip());
            sicDescription = sub.path("sicDescription").asString(null);
            if (name == null) {
                name = sub.path("name").asString(null);
            }
        } catch (DataSourceException | NumberFormatException e) {
            // The profile only refines the analysis (bank detection); carry on without it.
        }
        return Optional.of(new CompanyRef(cik, ticker, name, sic, sicDescription));
    }

    @Override
    public CompanyFacts companyFacts(long cik) throws DataSourceException {
        JsonNode root = getJson(dataBaseUrl + String.format("/api/xbrl/companyfacts/CIK%010d.json", cik),
                String.format("companyfacts/CIK%010d.json", cik), FACTS_MAX_AGE);
        return parse(root);
    }

    /** Companyfacts JSON to domain facts. Public for tests and the parity tool. */
    public static CompanyFacts parse(JsonNode root) {
        long cik = root.path("cik").asLong();
        Map<String, List<Fact>> byConcept = new HashMap<>();
        for (Map.Entry<String, JsonNode> tax : root.path("facts").properties()) {
            for (Map.Entry<String, JsonNode> concept : tax.getValue().properties()) {
                String key = tax.getKey() + ":" + concept.getKey();
                List<Fact> out = byConcept.computeIfAbsent(key, k -> new ArrayList<>());
                for (Map.Entry<String, JsonNode> unit : concept.getValue().path("units").properties()) {
                    for (JsonNode r : unit.getValue().values()) {
                        String start = r.path("start").asString("");
                        out.add(new Fact(key, r.path("val").decimalValue(), unit.getKey(),
                                LocalDate.parse(r.path("end").asString()),
                                start.isEmpty() ? null : LocalDate.parse(start),
                                r.path("form").asString(""), r.path("accn").asString(),
                                LocalDate.parse(r.path("filed").asString()), cik));
                    }
                }
            }
        }
        return new CompanyFacts(cik, root.path("entityName").asString(""), byConcept);
    }

    public static CompanyFacts parse(String jsonText) {
        return parse(new ObjectMapper().readTree(jsonText));
    }
}
