package com.example.payment.dto;

import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentType;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, PaymentType type, String status,
                              long amount, String currency, Instant createdAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getType(), payment.getStatus().name(),
                payment.getAmount(), payment.getCurrency(), payment.getCreatedAt());
    }
}
