package com.example.payment.vendor;

import com.example.vendor.api.VendorPayment;

import java.util.UUID;

public interface VendorGateway {
    /** Rejected on 422 or when the vendor returns the payment as REJECTED. */
    VendorResult<VendorPayment> createPayout(UUID reference, long amount, String currency);

    /** Rejected on 422 or when the vendor returns the payment as REJECTED. */
    VendorResult<VendorPayment> confirm(String vendorPaymentId);

    /** Rejected(404) when the vendor doesn't know the id. The caller checks type and status. */
    VendorResult<VendorPayment> get(String vendorPaymentId);
}
