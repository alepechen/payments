package com.example.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDepositRequest(@NotBlank String vendorPaymentId) {}
