package com.example.payment.vendor;

import com.example.vendor.api.VendorPayment;

import java.util.UUID;

public interface VendorGateway {
    VendorPayment createPayout(UUID reference, long amount, String currency);
    VendorPayment confirm(String vendorPaymentId);
    VendorPayment get(String vendorPaymentId);
}
