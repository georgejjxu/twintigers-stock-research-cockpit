package ca.twintigers.research.adapters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small stateful stand-in for Ghostfolio 3.x's REST API, enough for the writer contract: login, accounts, and
 * activities (list, create, update, delete), with Ghostfolio's validation of required fields and a JWT check.
 */
final class FakeGhostfolioServer implements AutoCloseable {

    static final String TOKEN = "fake-security-token";
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    final Map<String, Map<String, Object>> activities = new LinkedHashMap<>();
    final Map<String, String> accounts = new LinkedHashMap<>(); // id -> name
    final Map<String, java.math.BigDecimal> balances = new LinkedHashMap<>(); // id -> cash
    int writes;

    FakeGhostfolioServer() throws IOException {
        accounts.put("acc-cad", "Cash CAD");
        accounts.put("acc-usd", "Cash USD");
        accounts.put("acc-rrsp", "RRSP");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        try {
            if (path.equals("/api/v1/auth/anonymous")) {
                JsonNode b = json.readTree(x.getRequestBody().readAllBytes());
                if (!TOKEN.equals(b.path("accessToken").asString(""))) {
                    send(x, 403, Map.of("message", "Forbidden"));
                } else {
                    send(x, 200, Map.of("authToken", "jwt-1"));
                }
                return;
            }
            if (!"Bearer jwt-1".equals(x.getRequestHeaders().getFirst("Authorization"))) {
                send(x, 401, Map.of("message", "Unauthorized"));
                return;
            }
            if (path.equals("/api/v1/account") && method.equals("GET")) {
                List<Map<String, Object>> list = new ArrayList<>();
                accounts.forEach((id, name) -> {
                    Map<String, Object> a = new LinkedHashMap<>(Map.of("id", id, "name", name, "currency", name.contains("USD") ? "USD" : "CAD",
                            "balance", balances.getOrDefault(id, java.math.BigDecimal.ZERO), "balanceInBaseCurrency", 0, "isExcluded", false));
                    a.put("comment", null);
                    a.put("platformId", null);
                    list.add(a);
                });
                send(x, 200, Map.of("accounts", list, "totalBalanceInBaseCurrency", 0));
            } else if (path.startsWith("/api/v1/account/") && method.equals("PUT")) {
                // Ghostfolio's UpdateAccountDto: id must match the path; name, currency and a numeric balance are required
                String id = path.substring("/api/v1/account/".length());
                JsonNode b = json.readTree(x.getRequestBody().readAllBytes());
                if (!accounts.containsKey(id) || !id.equals(b.path("id").asString("")) || b.path("name").asString("").isEmpty()
                        || b.path("currency").asString("").isEmpty() || !b.path("balance").isNumber()
                        || b.propertyNames().stream().anyMatch(k -> !List.of("balance", "comment", "currency", "id", "name", "platformId").contains(k))) {
                    // Ghostfolio's validation pipe also rejects properties the DTO does not declare
                    send(x, 400, Map.of("message", List.of("invalid account")));
                    return;
                }
                balances.put(id, b.path("balance").decimalValue());
                writes++;
                send(x, 200, Map.of("id", id));
            } else if (path.equals("/api/v1/activities") && method.equals("GET")) {
                List<Map<String, Object>> list = new ArrayList<>();
                activities.values().forEach(a -> {
                    Map<String, Object> o = new LinkedHashMap<>(a);
                    o.put("account", Map.of("name", accounts.get((String) a.get("accountId"))));
                    o.put("assetProfile", Map.of("symbol", a.get("symbol")));
                    list.add(o);
                });
                send(x, 200, Map.of("activities", list, "count", list.size()));
            } else if (path.equals("/api/v1/activities") && method.equals("POST")) {
                Map<String, Object> a = validated(json.readTree(x.getRequestBody().readAllBytes()));
                if (a == null) {
                    send(x, 400, Map.of("message", List.of("invalid activity")));
                    return;
                }
                String id = UUID.randomUUID().toString();
                a.put("id", id);
                activities.put(id, a);
                writes++;
                send(x, 201, a);
            } else if (path.startsWith("/api/v1/activities/") && method.equals("PUT")) {
                String id = path.substring("/api/v1/activities/".length());
                Map<String, Object> a = validated(json.readTree(x.getRequestBody().readAllBytes()));
                if (!activities.containsKey(id) || a == null) {
                    send(x, a == null ? 400 : 403, Map.of("message", "Forbidden"));
                    return;
                }
                a.put("id", id);
                activities.put(id, a);
                writes++;
                send(x, 200, a);
            } else if (path.startsWith("/api/v1/activities/") && method.equals("DELETE")) {
                String id = path.substring("/api/v1/activities/".length());
                if (activities.remove(id) == null) {
                    send(x, 403, Map.of("message", "Forbidden"));
                    return;
                }
                writes++;
                send(x, 200, Map.of("id", id));
            } else {
                send(x, 404, Map.of("message", "Not Found"));
            }
        } finally {
            x.close();
        }
    }

    /** Mirrors Ghostfolio's DTO validation for the fields the adapter sends. */
    private Map<String, Object> validated(JsonNode b) {
        String acc = b.path("accountId").asString("");
        if (!accounts.containsKey(acc) || b.path("symbol").asString("").isEmpty() || b.path("quantity").asDouble(-1) < 0
                || b.path("unitPrice").asDouble(-1) < 0 || !b.path("date").asString("").contains("T")) {
            return null;
        }
        Map<String, Object> a = new LinkedHashMap<>();
        for (String f : List.of("accountId", "comment", "currency", "dataSource", "date", "symbol", "type")) {
            a.put(f, b.path(f).isNull() ? null : b.path(f).asString(null));
        }
        for (String f : List.of("fee", "quantity", "unitPrice")) {
            a.put(f, b.path(f).decimalValue());
        }
        return a;
    }

    private void send(HttpExchange x, int status, Object body) throws IOException {
        byte[] b = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
    }
}
