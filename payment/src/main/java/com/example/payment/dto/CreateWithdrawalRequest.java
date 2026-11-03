package com.example.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateWithdrawalRequest(
        @NotBlank @Size(min = 8, max = 64) String idempotencyKey,
        @Positive long amount,
        @NotBlank @Size(min = 3, max = 3) String currency) {}

