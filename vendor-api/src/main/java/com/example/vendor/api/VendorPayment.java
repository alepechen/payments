package com.example.vendor.api;

import java.util.UUID;

public record VendorPayment(String id, UUID reference, VendorPaymentType type,
                            VendorPaymentStatus status, long amount, String currency) {}
