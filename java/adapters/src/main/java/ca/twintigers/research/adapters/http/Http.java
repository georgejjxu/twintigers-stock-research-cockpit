package ca.twintigers.research.adapters.http;

import ca.twintigers.research.ports.DataSourceException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thin HTTP wrapper: timeouts, and Resilience4j retries with exponential backoff for network errors,
 * HTTP 429 and 5xx. Other 4xx answers are returned to the caller, which decides what they mean.
 * Error messages contain the host and path only, never headers or tokens.
 */
public final class Http {

    public record Response(int status, String body) {
    }

    private static final class RetryableStatus extends RuntimeException {
        final int status;

        RetryableStatus(int status) {
            super("HTTP " + status, null, false, false);
            this.status = status;
        }
    }

    private final HttpClient client;
    private final Retry retry;
    private final Duration timeout;

    public Http(Duration timeout, int maxAttempts) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.timeout = timeout;
        this.retry = Retry.of("http", RetryConfig.custom().maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2.0))
                .retryOnException(e -> e instanceof IOException || e instanceof RetryableStatus)
                .build());
    }

    public Http() {
        this(Duration.ofSeconds(30), 3);
    }

    public Response get(URI uri, Map<String, String> headers) throws DataSourceException {
        return send(() -> request(uri, headers).GET().build(), uri);
    }

    public Response post(URI uri, Map<String, String> headers, String json) throws DataSourceException {
        return send(() -> request(uri, headers).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), uri);
    }

    public Response put(URI uri, Map<String, String> headers, String json) throws DataSourceException {
        return send(() -> request(uri, headers).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), uri);
    }

    public Response delete(URI uri, Map<String, String> headers) throws DataSourceException {
        return send(() -> request(uri, headers).DELETE().build(), uri);
    }

    private HttpRequest.Builder request(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        return b;
    }

    private Response send(Supplier<HttpRequest> req, URI uri) throws DataSourceException {
        try {
            return Retry.decorateCheckedSupplier(retry, () -> {
                HttpResponse<String> r = client.send(req.get(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429 || r.statusCode() >= 500) {
                    throw new RetryableStatus(r.statusCode());
                }
                return new Response(r.statusCode(), r.body());
            }).get();
        } catch (RetryableStatus e) {
            throw new DataSourceException(where(uri) + " answered HTTP " + e.status + " (after retries).");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new DataSourceException(where(uri) + " did not answer in time.", e);
        } catch (IOException e) {
            throw new DataSourceException(where(uri) + " is not reachable (" + e.getClass().getSimpleName() + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException("Interrupted while calling " + where(uri) + ".", e);
        } catch (Throwable e) {
            throw new DataSourceException("Call to " + where(uri) + " failed: " + e.getClass().getSimpleName(), e);
        }
    }

    static String where(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
    }
}
