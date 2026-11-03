package com.example.payment.exception;

import com.example.payment.domain.WithdrawalStatus;

import java.util.UUID;

public class IllegalStateTransitionException extends RuntimeException {

    private final UUID paymentId;
    private final WithdrawalStatus from;
    private final WithdrawalStatus to;

    public IllegalStateTransitionException(UUID paymentId, WithdrawalStatus from, WithdrawalStatus to) {
        super("Payment %s: transition %s -> %s is not allowed".formatted(paymentId, from, to));
        this.paymentId = paymentId;
        this.from = from;
        this.to = to;
    }

    public UUID getPaymentId() { return paymentId; }
    public WithdrawalStatus getFrom() { return from; }
    public WithdrawalStatus getTo() { return to; }
}
