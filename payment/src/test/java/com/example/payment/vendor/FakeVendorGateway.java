package com.example.payment.vendor;

import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Scriptable vendor for tests. Queue results per call; when a queue is empty the call succeeds.
 * Use with {@code @Import(FakeVendorGateway.Config.class)} and call {@link #reset()} before each test.
 */
public class FakeVendorGateway implements VendorGateway {

    private final Deque<VendorResult<VendorPayment>> createResults = new ArrayDeque<>();
    private final Deque<VendorResult<VendorPayment>> confirmResults = new ArrayDeque<>();
    private final List<UUID> createCalls = new ArrayList<>();
    private final List<String> confirmCalls = new ArrayList<>();

    public synchronized void reset() {
        createResults.clear();
        confirmResults.clear();
        createCalls.clear();
        confirmCalls.clear();
    }

    /** The next {@code times} createPayout calls return {@code result}. */
    public synchronized FakeVendorGateway onCreate(VendorResult<VendorPayment> result, int times) {
        for (int i = 0; i < times; i++) createResults.add(result);
        return this;
    }

    public FakeVendorGateway onCreate(VendorResult<VendorPayment> result) {
        return onCreate(result, 1);
    }

    /** The next {@code times} confirm calls return {@code result}. */
    public synchronized FakeVendorGateway onConfirm(VendorResult<VendorPayment> result, int times) {
        for (int i = 0; i < times; i++) confirmResults.add(result);
        return this;
    }

    public FakeVendorGateway onConfirm(VendorResult<VendorPayment> result) {
        return onConfirm(result, 1);
    }

    public synchronized List<UUID> createCalls() {
        return List.copyOf(createCalls);
    }

    public synchronized List<String> confirmCalls() {
        return List.copyOf(confirmCalls);
    }

    /** The vendor id the fake assigns to a payout with this reference. */
    public static String vendorIdFor(UUID reference) {
        return "vp-" + reference;
    }

    @Override
    public synchronized VendorResult<VendorPayment> createPayout(UUID reference, long amount, String currency) {
        createCalls.add(reference);
        VendorResult<VendorPayment> scripted = createResults.poll();
        return scripted != null ? scripted : new VendorResult.Success<>(new VendorPayment(
                vendorIdFor(reference), reference, VendorPaymentType.PAYOUT, VendorPaymentStatus.CREATED, amount, currency));
    }

    @Override
    public synchronized VendorResult<VendorPayment> confirm(String vendorPaymentId) {
        confirmCalls.add(vendorPaymentId);
        VendorResult<VendorPayment> scripted = confirmResults.poll();
        return scripted != null ? scripted : new VendorResult.Success<>(new VendorPayment(
                vendorPaymentId, null, VendorPaymentType.PAYOUT, VendorPaymentStatus.COMPLETED, 0, null));
    }

    @Override
    public VendorResult<VendorPayment> get(String vendorPaymentId) {
        throw new UnsupportedOperationException("not scripted yet");
    }

    /** Replaces {@link VendorClient} as the {@link VendorGateway} the app injects. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FakeVendorGateway fakeVendorGateway() {
            return new FakeVendorGateway();
        }
    }
}
