package io.kestra.plugin.qlikcloud;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

/**
 * Thin wrapper around Kestra's HTTP client for the Qlik Cloud REST API: bearer auth, 429 handling
 * (honoring {@code Retry-After}, capped exponential backoff), 5xx retry restricted to GET, and
 * {@code errors[].title/detail} error parsing. Reused across a single task run (trigger + every
 * poll), so it must be closed once by the caller.
 */
public final class QlikCloudClient implements Closeable {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    // Qlik answers these with a 429 even though they are not a rate limit (e.g. a reload already
    // pending for the same app): retrying them just delays an error that will never resolve on its own.
    // Note: the underlying Apache HttpClient (built by Kestra's own HttpClient, which exposes no
    // retry configuration) already retries a 429/503 once on its own, honoring Retry-After, before our
    // code ever sees the response — this is unavoidable through Kestra's public HTTP client API. What
    // we control is not adding any further, additional backoff on top of that single hidden retry once
    // we do see one of these codes.
    private static final Set<String> NON_RETRIABLE_429_ERROR_CODES = Set.of("RELOADS-007");

    private final HttpClient httpClient;
    private final String rTenantUrl;
    private final String rApiKey;

    private QlikCloudClient(HttpClient httpClient, String rTenantUrl, String rApiKey) {
        this.httpClient = httpClient;
        this.rTenantUrl = rTenantUrl;
        this.rApiKey = rApiKey;
    }

    public static QlikCloudClient of(RunContext runContext, String rTenantUrl, String rApiKey) throws IllegalVariableEvaluationException {
        return new QlikCloudClient(new HttpClient(runContext, null), normalizeTenantUrl(rTenantUrl), rApiKey);
    }

    static String normalizeTenantUrl(String tenantUrl) {
        String trimmed = tenantUrl.strip();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException(
                "`tenantUrl` must be a full URL starting with http:// or https://, got '" + tenantUrl + "'"
            );
        }
        String stripped = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (stripped.endsWith("/api/v1")) {
            throw new IllegalArgumentException(
                "`tenantUrl` must be the tenant root, not the API path: remove the trailing `/api/v1` " +
                    "(e.g. use `https://mytenant.eu.qlikcloud.com`)"
            );
        }
        return stripped;
    }

    /** GET, JSON body. Retries both 429 (honoring {@code Retry-After}) and 5xx. */
    public JsonNode get(String pathOrUrl) throws IOException {
        return request("GET", pathOrUrl, null, true);
    }

    /**
     * POST, JSON body (or no body when {@code body} is null). Retries only 429: a 5xx or a
     * transport failure is never retried here, so the trigger POST and the cancel/stop actions
     * are never silently duplicated — see {@link io.kestra.plugin.qlikcloud.AbstractQlikCloudRun}.
     */
    public JsonNode post(String pathOrUrl, Object body) throws IOException {
        return request("POST", pathOrUrl, body, false);
    }

    /** GET as raw text, used for the plain-text reload log endpoint. Never retried: best-effort, caller falls back. */
    public String getRaw(String pathOrUrl) throws IOException {
        HttpRequest request = HttpRequest.builder()
            .uri(resolveUri(pathOrUrl))
            .method("GET")
            .addHeader("Authorization", "Bearer " + rApiKey)
            .addHeader("Accept", "text/plain")
            .build();

        try {
            HttpResponse<String> response = httpClient.request(request, String.class);
            return response.getBody();
        } catch (HttpClientResponseException e) {
            throw translateError("GET", pathOrUrl, e);
        } catch (IllegalVariableEvaluationException | HttpClientException e) {
            throw new IOException("Failed to call the Qlik Cloud API GET " + pathOrUrl + ": " + e.getMessage(), e);
        }
    }

    private JsonNode request(String method, String pathOrUrl, Object body, boolean retryServerErrors) throws IOException {
        URI uri = resolveUri(pathOrUrl);
        Duration backoff = INITIAL_BACKOFF;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest.HttpRequestBuilder builder = HttpRequest.builder()
                .uri(uri)
                .method(method)
                .addHeader("Authorization", "Bearer " + rApiKey)
                .addHeader("Accept", "application/json");

            if (body != null) {
                builder.addHeader("Content-Type", "application/json")
                    .body(HttpRequest.JsonRequestBody.builder().content(body).build());
            }

            try {
                HttpResponse<String> response = httpClient.request(builder.build(), String.class);
                String raw = response.getBody();
                return raw == null || raw.isBlank() ? NullNode.getInstance() : JacksonMapper.ofJson().readTree(raw);
            } catch (HttpClientResponseException e) {
                int code = e.getResponse().getStatus().getCode();
                boolean lastAttempt = attempt == MAX_ATTEMPTS;

                if (code == 429 && !lastAttempt && !NON_RETRIABLE_429_ERROR_CODES.contains(extractErrorCode(e))) {
                    sleep(retryAfter(e).orElse(backoff));
                    backoff = cappedDouble(backoff);
                    continue;
                }
                if (retryServerErrors && code >= 500 && !lastAttempt) {
                    sleep(backoff);
                    backoff = cappedDouble(backoff);
                    continue;
                }
                throw translateError(method, pathOrUrl, e);
            } catch (IllegalVariableEvaluationException e) {
                throw new IOException("Failed to render the Qlik Cloud request for " + method + " " + pathOrUrl, e);
            } catch (HttpClientException e) {
                // Transport-level failure (connect/read timeout, TLS, ...): retried like a 5xx for GET only, so
                // the non-idempotent trigger POST is never blindly retried on an ambiguous failure.
                if (retryServerErrors && attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                    backoff = cappedDouble(backoff);
                    continue;
                }
                throw new IOException("Failed to call the Qlik Cloud API " + method + " " + pathOrUrl + ": " + e.getMessage(), e);
            }
        }

        throw new IOException("Exhausted retries calling the Qlik Cloud API " + method + " " + pathOrUrl);
    }

    private URI resolveUri(String pathOrUrl) {
        return pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")
            ? URI.create(pathOrUrl)
            : URI.create(rTenantUrl + pathOrUrl);
    }

    // Qlik Cloud sends Retry-After as an integer number of seconds, not an HTTP-date, so only that form is parsed.
    private static Optional<Duration> retryAfter(HttpClientResponseException e) {
        return e.getResponse().getHeaders().firstValue("Retry-After")
            .map(String::trim)
            .flatMap(value -> {
                try {
                    return Optional.of(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            })
            .map(seconds -> capped(Duration.ofSeconds(seconds)));
    }

    private static Duration capped(Duration duration) {
        return duration.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : duration;
    }

    private static Duration cappedDouble(Duration duration) {
        return capped(duration.multipliedBy(2));
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while backing off a Qlik Cloud API retry", e);
        }
    }

    private static QlikCloudApiException translateError(String method, String pathOrUrl, HttpClientResponseException e) {
        int code = e.getResponse().getStatus().getCode();
        String detail = extractErrorDetail(e);
        String errorCode = extractErrorCode(e);
        String message = "Qlik Cloud API call " + method + " " + pathOrUrl + " failed with HTTP " + code +
            (detail != null ? ": " + detail : "");
        return new QlikCloudApiException(message, code, errorCode, e);
    }

    private static String bodyAsString(HttpClientResponseException e) {
        Object rawBody = e.getResponse().getBody();
        return switch (rawBody) {
            case byte[] bytes when bytes.length > 0 -> new String(bytes, StandardCharsets.UTF_8);
            case String s when !s.isBlank() -> s;
            case null, default -> null;
        };
    }

    /** The first {@code errors[]} entry of the response body, or null when absent/not JSON. */
    private static JsonNode firstError(String bodyString) {
        if (bodyString == null) {
            return null;
        }
        try {
            JsonNode errors = JacksonMapper.ofJson().readTree(bodyString).path("errors");
            return errors.isArray() && !errors.isEmpty() ? errors.get(0) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    static String extractErrorCode(HttpClientResponseException e) {
        JsonNode first = firstError(bodyAsString(e));
        return first != null ? first.path("code").asText(null) : null;
    }

    private static String extractErrorDetail(HttpClientResponseException e) {
        String bodyString = bodyAsString(e);
        JsonNode first = firstError(bodyString);
        if (first != null) {
            String title = first.path("title").asText(null);
            String detail = first.path("detail").asText(null);
            String joined = Stream.of(title, detail).filter(Objects::nonNull).collect(Collectors.joining(" - "));
            return joined.isBlank() ? bodyString : joined;
        }
        return bodyString;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
