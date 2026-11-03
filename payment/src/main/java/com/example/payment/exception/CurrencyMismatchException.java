package com.example.payment.exception;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String accountCurrency, String requested) {
        super("Currency mismatch: account %s, request %s".formatted(accountCurrency, requested));
    }
}
