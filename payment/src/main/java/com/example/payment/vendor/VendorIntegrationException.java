package com.example.payment.vendor;

/**
 * Something a person has to fix: the vendor refused our credentials, or answered with a body we
 * can't read. Not retried and not counted by the circuit breaker.
 */
public class VendorIntegrationException extends RuntimeException {

    public VendorIntegrationException(String message) {
        super(message);
    }

    public VendorIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
