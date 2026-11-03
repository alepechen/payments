package com.example.vendor.api;

public final class VendorPaths {
    public static final String PAYMENT = "/payment";
    public static final String PAYMENT_BY_ID = PAYMENT + "/{id}";
    public static final String PAYMENT_CONFIRM = PAYMENT_BY_ID + "/confirm";

    private VendorPaths() {}
}
