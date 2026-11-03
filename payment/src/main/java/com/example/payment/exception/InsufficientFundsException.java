package com.example.payment.exception;

public class InsufficientFundsException extends RuntimeException {

        private final Long accountId;
        private final long requested;
        private final long available;

        public InsufficientFundsException(Long accountId, long requested, long available) {
            super("Account %d: requested %d, available %d".formatted(accountId, requested, available));
            this.accountId = accountId;
            this.requested = requested;
            this.available = available;
        }

        public Long getAccountId() { return accountId; }
        public long getRequested() { return requested; }
        public long getAvailable() { return available; }
    }
