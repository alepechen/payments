package com.example.payment.exception;

import java.time.Duration;

/** The vendor couldn't be reached or refused to take calls right now. The client may retry later. */
public class VendorUnavailableException extends RuntimeException {

    private final Duration retryAfter;   // null when the vendor didn't say

    public VendorUnavailableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
