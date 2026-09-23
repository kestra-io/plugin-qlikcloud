package io.kestra.plugin.qlikcloud;

import java.io.IOException;

/**
 * A failed Qlik Cloud REST API call, carrying the HTTP status code so callers can react to
 * specific codes (e.g. 404 on a reattach lookup) without re-parsing the message.
 */
public class QlikCloudApiException extends IOException {
    private final int statusCode;

    public QlikCloudApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
