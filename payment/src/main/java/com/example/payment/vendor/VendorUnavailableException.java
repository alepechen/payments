package com.example.payment.vendor;

/**
 * The vendor could not be reached or answered with a 5xx (timeout, connection refused, outage).
 * Retryable: the call is idempotent, so the worker backs off and tries again.
 */
public class VendorUnavailableException extends RuntimeException {

    public VendorUnavailableException(String message) {
        super(message);
    }

    public VendorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
