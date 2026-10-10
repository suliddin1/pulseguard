package com.pulseguard.notification.channel;

/**
 * Outcome of one delivery attempt. {@code message} is already redacted and safe to persist.
 */
public record DeliveryResult(Kind kind, Integer httpStatus, String message) {

    public enum Kind {
        SUCCESS,
        /** Worth retrying: network trouble, timeouts, 5xx, 429. */
        RETRYABLE,
        /** Retrying cannot help: policy violation, 4xx, redirects, bad configuration. */
        PERMANENT
    }

    public static DeliveryResult success(int httpStatus) {
        return new DeliveryResult(Kind.SUCCESS, httpStatus, null);
    }

    public static DeliveryResult retryable(Integer httpStatus, String message) {
        return new DeliveryResult(Kind.RETRYABLE, httpStatus, message);
    }

    public static DeliveryResult permanent(Integer httpStatus, String message) {
        return new DeliveryResult(Kind.PERMANENT, httpStatus, message);
    }

    /** Classifies an HTTP status code returned by a destination. */
    public static DeliveryResult fromHttpStatus(int status) {
        if (status >= 200 && status < 300) {
            return success(status);
        }
        if (status >= 300 && status < 400) {
            return permanent(status, "http_" + status + "_redirect_not_followed");
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return retryable(status, "http_" + status);
        }
        if (status >= 400) {
            return permanent(status, "http_" + status);
        }
        return retryable(status, "http_" + status + "_unexpected");
    }
}
