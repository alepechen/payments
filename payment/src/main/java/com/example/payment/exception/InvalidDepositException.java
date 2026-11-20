package com.example.payment.exception;

/** The vendor payment can't be credited: unknown, not a completed payin, or in the wrong currency. */
public class InvalidDepositException extends RuntimeException {
    public InvalidDepositException(String message) {
        super(message);
    }
}
