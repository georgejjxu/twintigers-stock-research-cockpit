package ca.twintigers.research.adapters.notify;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.Notifier;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Push to the user's phone through ntfy (https://ntfy.sh, or a self-hosted server): the free ntfy app on iOS or
 * Android subscribes to the topic. Uses ntfy's JSON publish API. Anyone who knows the topic name can read it,
 * so the topic is a long random string kept in secrets.env (NTFY_TOPIC); an access token (NTFY_TOKEN) is sent
 * when configured (self-hosted servers with authentication). Neither appears in errors.
 */
public final class NtfyNotifier implements Notifier {

    private final Http http;
    private final String baseUrl;
    private final String topic;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();

    public NtfyNotifier(Http http, String baseUrl, String topic, String token) {
        this.http = http;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://ntfy.sh" : baseUrl).replaceAll("/+$", "");
        this.topic = topic;
        this.token = token;
    }

    @Override
    public String channel() {
        return "ntfy";
    }

    @Override
    public void send(Notification n) throws DataSourceException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("topic", topic);
        msg.put("title", n.title());
        msg.put("message", n.body());
        msg.put("priority", switch (n.priority()) {
            case HIGH -> 4;
            case DEFAULT -> 3;
            case LOW -> 2;
        });
        msg.put("tags", List.of(n.priority() == Notification.Priority.HIGH ? "warning" : "chart_with_downwards_trend"));
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        Http.Response r = http.post(URI.create(baseUrl + "/"), headers, json.writeValueAsString(msg));
        if (r.status() >= 300) {
            throw new DataSourceException("ntfy answered HTTP " + r.status() + ".");
        }
    }
}
