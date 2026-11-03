package com.example.payment.vendor;

/**
 * The vendor definitively refused the payment (for example HTTP 422 or a REJECTED status).
 * Not retryable: the worker releases the hold and declines the withdrawal.
 */
public class VendorRejectedException extends RuntimeException {

    public VendorRejectedException(String message) {
        super(message);
    }

    public VendorRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
