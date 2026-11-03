package com.example.vendor.api;

import java.util.UUID;

public record CreatePayoutRequest(UUID reference, long amount, String currency) {}
