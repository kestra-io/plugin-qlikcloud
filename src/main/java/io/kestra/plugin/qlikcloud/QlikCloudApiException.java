package io.kestra.plugin.qlikcloud;

import java.io.IOException;

/**
 * A failed Qlik Cloud REST API call, carrying the HTTP status code and Qlik's own error code
 * (e.g. {@code RELOADS-013}) so callers can react to specific cases — a 404 on the trigger POST,
 * a stale reattach lookup, or a 429 that is not actually a rate limit — without re-parsing the message.
 */
public class QlikCloudApiException extends IOException {
    private final int statusCode;
    private final String errorCode;

    public QlikCloudApiException(String message, int statusCode, String errorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Qlik's own {@code errors[].code} (e.g. {@code RELOADS-013}), or null when absent/unparsable. */
    public String errorCode() {
        return errorCode;
    }
}
