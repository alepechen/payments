package com.example.payment.dto;

import com.example.payment.domain.Payment;

public record CreateResult(Payment payment, boolean created) {}
