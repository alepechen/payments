package com.example.payment.vendor;

import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scriptable vendor for tests. Queue results per call; when a queue is empty, createPayout and confirm
 * succeed, and get returns a payment registered with {@link #given} or Rejected(404).
 * Use with {@code @Import(FakeVendorGateway.Config.class)} and call {@link #reset()} before each test.
 */
public class FakeVendorGateway implements VendorGateway {

    private final Deque<VendorResult<VendorPayment>> createResults = new ArrayDeque<>();
    private final Deque<VendorResult<VendorPayment>> confirmResults = new ArrayDeque<>();
    private final List<UUID> createCalls = new ArrayList<>();
    private final List<String> confirmCalls = new ArrayList<>();
    private final Deque<VendorResult<VendorPayment>> getResults = new ArrayDeque<>();
    private final Map<String, VendorPayment> known = new HashMap<>();
    private final List<String> getCalls = new ArrayList<>();

    public synchronized void reset() {
        createResults.clear();
        confirmResults.clear();
        createCalls.clear();
        confirmCalls.clear();
        getResults.clear();
        known.clear();
        getCalls.clear();
    }

    /** The vendor knows this payment: get returns it (unless a scripted result is queued). */
    public synchronized FakeVendorGateway given(VendorPayment payment) {
        known.put(payment.id(), payment);
        return this;
    }

    /** A completed payin the vendor will return from get. */
    public FakeVendorGateway givenPayin(String id, long amount, String currency) {
        return given(new VendorPayment(id, null, VendorPaymentType.PAYIN, VendorPaymentStatus.COMPLETED, amount, currency));
    }

    /** The next get call returns {@code result}. */
    public synchronized FakeVendorGateway onGet(VendorResult<VendorPayment> result) {
        getResults.add(result);
        return this;
    }

    public synchronized List<String> getCalls() {
        return List.copyOf(getCalls);
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

    /** Scripted result first, then a known payment, otherwise Rejected(404) like the real vendor. */
    @Override
    public synchronized VendorResult<VendorPayment> get(String vendorPaymentId) {
        getCalls.add(vendorPaymentId);
        VendorResult<VendorPayment> scripted = getResults.poll();
        if (scripted != null) return scripted;
        VendorPayment payment = known.get(vendorPaymentId);
        return payment != null ? new VendorResult.Success<>(payment) : new VendorResult.Rejected<>(404, "not found");
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
