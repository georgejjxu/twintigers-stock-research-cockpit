package ca.twintigers.research.adapters.ghostfolio;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.PortfolioSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghostfolio REST client. The security token (GHOSTFOLIO_TOKEN) is exchanged for a JWT at POST /api/v1/auth/anonymous.
 * Reading uses GET endpoints only. Writing (create, update, delete activities) is OFF unless GHOSTFOLIO_WRITES=enabled,
 * validates every draft first, and touches only /api/v1/activities. The token never appears in errors.
 */
public final class GhostfolioClient implements PortfolioSource, ca.twintigers.research.ports.PortfolioWriter {

    private final Http http;
    private final String baseUrl;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    private final boolean writesEnabled;
    private final java.time.Clock clock;
    private String jwt;

    public GhostfolioClient(Http http, String baseUrl, String token) {
        this(http, baseUrl, token, false, java.time.Clock.systemDefaultZone());
    }

    public GhostfolioClient(Http http, String baseUrl, String token, boolean writesEnabled, java.time.Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl == null ? "http://localhost:3333" : baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.writesEnabled = writesEnabled;
        this.clock = clock;
    }

    private String login() throws DataSourceException {
        if (token == null || token.isBlank()) {
            throw new DataSourceException("GHOSTFOLIO_TOKEN is not set. Put the Ghostfolio security token in secrets.env at the project root.");
        }
        Map<String, String> body = Map.of("accessToken", token);
        Http.Response r = post("/api/v1/auth/anonymous", json.writeValueAsString(body));
        if (r.status() == 401 || r.status() == 403) {
            throw new DataSourceException("Ghostfolio rejected GHOSTFOLIO_TOKEN (the security token). Check secrets.env.");
        }
        if (r.status() >= 400) {
            throw new DataSourceException("Ghostfolio login failed: HTTP " + r.status() + ".");
        }
        String t = json.readTree(r.body()).path("authToken").asString("");
        if (t.isEmpty()) {
            throw new DataSourceException("Ghostfolio login returned no authToken.");
        }
        return t;
    }

    private Http.Response post(String path, String body) throws DataSourceException {
        try {
            return http.post(URI.create(baseUrl + path), Map.of(), body);
        } catch (DataSourceException e) {
            throw down(e);
        }
    }

    private DataSourceException down(DataSourceException e) {
        return new DataSourceException("Ghostfolio is not reachable at " + baseUrl + ". Start it with scripts\\start.ps1 "
                + "(Docker Desktop must be running). Detail: " + e.getMessage(), e);
    }

    /** GET a path as the token's user, logging in (again) when needed. */
    JsonNode get(String path) throws DataSourceException {
        return json.readTree(call("GET", path, null).body());
    }

    /** Any call as the token's user; renews the JWT once on 401. Errors carry the status and Ghostfolio's message, never the token. */
    private Http.Response call(String method, String path, String body) throws DataSourceException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (jwt == null) {
                jwt = login();
            }
            Http.Response r;
            Map<String, String> auth = Map.of("Authorization", "Bearer " + jwt);
            URI uri = URI.create(baseUrl + path);
            try {
                r = switch (method) {
                    case "GET" -> http.get(uri, auth);
                    case "POST" -> http.post(uri, auth, body);
                    case "PUT" -> http.put(uri, auth, body);
                    case "DELETE" -> http.delete(uri, auth);
                    default -> throw new IllegalArgumentException(method);
                };
            } catch (DataSourceException e) {
                throw down(e);
            }
            if (r.status() == 401 && attempt == 1) {
                jwt = null;
                continue;
            }
            if (r.status() >= 400) {
                String msg = "";
                try {
                    JsonNode m = json.readTree(r.body()).path("message");
                    msg = m.isArray() ? String.join("; ", m.values().stream().map(JsonNode::asString).toList()) : m.asString("");
                } catch (RuntimeException ignored) {
                    // not JSON
                }
                throw new DataSourceException("Ghostfolio " + method + " " + path.replaceAll("\\?.*", "") + " failed: HTTP " + r.status()
                        + (msg.isBlank() ? "." : ": " + msg));
            }
            return r;
        }
        throw new DataSourceException("Ghostfolio " + path + " failed: not authorized after a fresh login.");
    }

    // --- writing (PortfolioWriter) ------------------------------------------------------------------------

    @Override
    public boolean enabled() {
        return writesEnabled;
    }

    @Override
    public List<String> accountNames() throws DataSourceException {
        return accountIds().keySet().stream().toList();
    }

    private Map<String, String> accountIds() throws DataSourceException {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode a : get("/api/v1/account").path("accounts").values()) {
            out.put(a.path("name").asString(""), a.path("id").asString(""));
        }
        return out;
    }

    private void requireWrites() throws DataSourceException {
        if (!writesEnabled) {
            throw new DataSourceException("Writing to Ghostfolio is switched off. To let the Cockpit record transactions, "
                    + "add GHOSTFOLIO_WRITES=enabled to secrets.env and restart the Cockpit.");
        }
    }

    private Map<String, Object> body(ca.twintigers.research.domain.portfolio.ActivityDraft draft) throws DataSourceException {
        requireWrites();
        Map<String, String> ids = accountIds();
        List<String> problems = draft.problems(java.time.LocalDate.now(clock), List.copyOf(ids.keySet()));
        if (!problems.isEmpty()) {
            throw new DataSourceException(String.join(" ", problems));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("accountId", ids.get(draft.account()));
        b.put("comment", draft.comment());
        b.put("currency", draft.currency());
        b.put("dataSource", "YAHOO");
        b.put("date", draft.date() + "T00:00:00.000Z");
        b.put("fee", draft.fee());
        b.put("quantity", draft.quantity());
        b.put("symbol", draft.symbol());
        b.put("type", draft.type());
        b.put("unitPrice", draft.unitPrice());
        return b;
    }

    @Override
    public ca.twintigers.research.domain.portfolio.Activity create(ca.twintigers.research.domain.portfolio.ActivityDraft draft)
            throws DataSourceException {
        var d = draft.normalized();
        Http.Response r = call("POST", "/api/v1/activities", json.writeValueAsString(body(d)));
        String id = json.readTree(r.body()).path("id").asString("");
        return find(id);
    }

    @Override
    public ca.twintigers.research.domain.portfolio.Activity update(String id, ca.twintigers.research.domain.portfolio.ActivityDraft draft)
            throws DataSourceException {
        requireId(id);
        var d = draft.normalized();
        Map<String, Object> b = body(d);
        b.put("id", id);
        call("PUT", "/api/v1/activities/" + id, json.writeValueAsString(b));
        return find(id);
    }

    @Override
    public void delete(String id) throws DataSourceException {
        requireWrites();
        requireId(id);
        call("DELETE", "/api/v1/activities/" + id, null);
    }

    /** PUT /api/v1/account/{id} with the account as it is and the new balance (Ghostfolio keeps the dated history). */
    @Override
    public ca.twintigers.research.domain.portfolio.Account setCash(String account, BigDecimal cash) throws DataSourceException {
        requireWrites();
        JsonNode found = null;
        List<String> names = new ArrayList<>();
        for (JsonNode a : get("/api/v1/account").path("accounts").values()) {
            names.add(a.path("name").asString(""));
            if (a.path("name").asString("").equals(account)) {
                found = a;
            }
        }
        ca.twintigers.research.ports.PortfolioWriter.checkCash(account, cash, names);
        String id = found.path("id").asString("");
        requireId(id);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("balance", cash);
        b.put("comment", found.path("comment").isNull() ? null : found.path("comment").asString(null));
        b.put("currency", found.path("currency").asString(""));
        b.put("id", id);
        b.put("name", account);
        b.put("platformId", found.path("platformId").isNull() ? null : found.path("platformId").asString(null));
        call("PUT", "/api/v1/account/" + id, json.writeValueAsString(b));
        return accounts().stream().filter(a -> a.name().equals(account)).findFirst()
                .orElseThrow(() -> new DataSourceException("Ghostfolio did not return account " + account + " after the change."));
    }

    private static void requireId(String id) throws DataSourceException {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new DataSourceException("Not a valid activity id.");
        }
    }

    private ca.twintigers.research.domain.portfolio.Activity find(String id) throws DataSourceException {
        return activities().stream().filter(a -> id.equals(a.id())).findFirst()
                .orElseThrow(() -> new DataSourceException("Ghostfolio did not return activity " + id + " after writing it."));
    }

    @Override
    public String name() {
        return "ghostfolio";
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.EnumSet.allOf(Capability.class);
    }

    @Override
    public List<ca.twintigers.research.domain.portfolio.Account> accounts() throws DataSourceException {
        List<ca.twintigers.research.domain.portfolio.Account> out = new ArrayList<>();
        for (JsonNode a : get("/api/v1/account").path("accounts").values()) {
            out.add(new ca.twintigers.research.domain.portfolio.Account(a.path("name").asString(""),
                    a.path("currency").asString(null), a.path("balance").decimalValue(BigDecimal.ZERO),
                    a.path("balanceInBaseCurrency").isNumber() ? a.path("balanceInBaseCurrency").decimalValue() : null));
        }
        return out;
    }

    @Override
    public List<ca.twintigers.research.domain.portfolio.Activity> activities() throws DataSourceException {
        List<ca.twintigers.research.domain.portfolio.Activity> out = new ArrayList<>();
        int skip = 0;
        while (true) {
            JsonNode page = get("/api/v1/activities?skip=" + skip + "&take=250");
            List<JsonNode> acts = new ArrayList<>(page.path("activities").values());
            for (JsonNode a : acts) {
                JsonNode p = a.path("assetProfile").isMissingNode() ? a.path("SymbolProfile") : a.path("assetProfile");
                String date = a.path("date").asString("");
                out.add(new ca.twintigers.research.domain.portfolio.Activity(a.path("id").asString(null),
                        date.length() >= 10 ? java.time.LocalDate.parse(date.substring(0, 10)) : null,
                        a.path("type").asString(""), a.path("account").path("name").asString(null), p.path("symbol").asString(null),
                        a.path("quantity").decimalValue(BigDecimal.ZERO), a.path("unitPrice").decimalValue(BigDecimal.ZERO),
                        a.path("fee").decimalValue(BigDecimal.ZERO), a.path("currency").asString(null), a.path("comment").asString(null)));
            }
            skip += acts.size();
            if (acts.isEmpty() || skip >= page.path("count").asInt(0)) {
                break;
            }
        }
        out.sort(java.util.Comparator.comparing(ca.twintigers.research.domain.portfolio.Activity::date,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return out;
    }

    /** Asset class, sectors and countries from each holding's asset profile (cash lines skipped). */
    @Override
    public Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> profiles() throws DataSourceException {
        Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> out = new LinkedHashMap<>();
        for (JsonNode h : get("/api/v1/portfolio/holdings").path("holdings").values()) {
            JsonNode p = h.path("assetProfile");
            if ("LIQUIDITY".equals(p.path("assetClass").asString(""))) {
                continue;
            }
            Map<String, Double> sectors = new LinkedHashMap<>();
            for (JsonNode s : p.path("sectors").values()) {
                sectors.put(s.path("name").asString("?"), s.path("weight").asDouble(0));
            }
            Map<String, Double> countries = new LinkedHashMap<>();
            for (JsonNode c : p.path("countries").values()) {
                countries.put(c.path("code").asString("?"), c.path("weight").asDouble(0));
            }
            String symbol = p.path("symbol").asString("");
            out.put(symbol, new ca.twintigers.research.domain.portfolio.InstrumentProfile(symbol, p.path("assetClass").asString(null),
                    p.path("assetSubClass").asString(null), sectors, countries));
        }
        return out;
    }

    /** Each account's balance history (GET /api/v1/account/{id}/balances), oldest first. */
    @Override
    public List<ca.twintigers.research.domain.portfolio.CashBalance> cashHistory() throws DataSourceException {
        List<ca.twintigers.research.domain.portfolio.CashBalance> out = new ArrayList<>();
        for (JsonNode a : get("/api/v1/account").path("accounts").values()) {
            String id = a.path("id").asString("");
            if (!id.matches("[A-Za-z0-9-]{1,64}")) {
                continue;
            }
            for (JsonNode b : get("/api/v1/account/" + id + "/balances").path("balances").values()) {
                String date = b.path("date").asString("");
                if (date.length() >= 10 && b.path("value").isNumber()) {
                    out.add(new ca.twintigers.research.domain.portfolio.CashBalance(a.path("name").asString(""),
                            a.path("currency").asString(null), java.time.LocalDate.parse(date.substring(0, 10)), b.path("value").decimalValue()));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(ca.twintigers.research.domain.portfolio.CashBalance::date));
        return out;
    }

    @Override
    public java.util.Optional<ca.twintigers.research.domain.portfolio.Performance> performance(String range) throws DataSourceException {
        String r = range == null || range.isBlank() ? "max" : range.strip().toLowerCase(java.util.Locale.ROOT);
        JsonNode p = get("/api/v2/portfolio/performance?range=" + java.net.URLEncoder.encode(r, java.nio.charset.StandardCharsets.UTF_8))
                .path("performance");
        BigDecimal pct = p.path("netPerformancePercentageWithCurrencyEffect").isNumber()
                ? p.path("netPerformancePercentageWithCurrencyEffect").decimalValue().multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_EVEN) : null;
        return java.util.Optional.of(new ca.twintigers.research.domain.portfolio.Performance(r,
                p.path("currentValueInBaseCurrency").decimalValue(null), p.path("totalInvestment").decimalValue(null),
                p.path("netPerformanceWithCurrencyEffect").decimalValue(null), pct,
                "Ghostfolio return on average investment (ROAI), incl. dividends and currency effect"));
    }

    @Override
    public Portfolio portfolio() throws DataSourceException {
        String base = get("/api/v1/user").path("settings").path("baseCurrency").asString(null);
        JsonNode accounts = get("/api/v1/account");
        BigDecimal cash = accounts.path("totalBalanceInBaseCurrency").decimalValue(BigDecimal.ZERO);
        Map<String, Map<String, BigDecimal>> split = sharesByAccount();
        List<Portfolio.Position> positions = new ArrayList<>();
        for (JsonNode h : get("/api/v1/portfolio/holdings?range=max").path("holdings").values()) {
            JsonNode p = h.path("assetProfile");
            if ("LIQUIDITY".equals(p.path("assetClass").asString("")) || "CASH".equals(p.path("assetSubClass").asString(""))) {
                continue;
            }
            String symbol = p.path("symbol").asString();
            positions.add(new Portfolio.Position(symbol, p.path("name").asString(null), p.path("currency").asString(null),
                    h.path("quantity").decimalValue(BigDecimal.ZERO),
                    h.path("marketPrice").decimalValue(BigDecimal.ZERO),
                    h.path("valueInBaseCurrency").decimalValue(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN),
                    split.getOrDefault(symbol, Map.of())));
        }
        positions.sort((a, b) -> b.valueInBase().compareTo(a.valueInBase()));
        return new Portfolio(base, positions, cash.setScale(2, RoundingMode.HALF_EVEN));
    }

    /** {symbol: {account: shares}} from BUY and SELL activities. */
    private Map<String, Map<String, BigDecimal>> sharesByAccount() throws DataSourceException {
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        int skip = 0;
        while (true) {
            JsonNode page = get("/api/v1/activities?skip=" + skip + "&take=250");
            List<JsonNode> acts = new ArrayList<>(page.path("activities").values());
            for (JsonNode a : acts) {
                String type = a.path("type").asString("");
                if (!type.equals("BUY") && !type.equals("SELL")) {
                    continue;
                }
                String symbol = a.path("assetProfile").path("symbol").asString(a.path("SymbolProfile").path("symbol").asString(""));
                String account = a.path("account").path("name").asString("(no account)");
                BigDecimal q = a.path("quantity").decimalValue(BigDecimal.ZERO);
                out.computeIfAbsent(symbol, k -> new LinkedHashMap<>())
                        .merge(account, type.equals("BUY") ? q : q.negate(), BigDecimal::add);
            }
            skip += acts.size();
            if (acts.isEmpty() || skip >= page.path("count").asInt(0)) {
                break;
            }
        }
        out.values().forEach(m -> m.values().removeIf(v -> v.signum() == 0));
        return out;
    }
}
