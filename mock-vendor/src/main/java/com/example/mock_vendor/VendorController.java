package com.example.mock_vendor;

import com.example.vendor.api.CreatePayoutRequest;
import com.example.vendor.api.VendorPaths;
import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fake vendor. All state lives in one map, so it is lost on restart.
 */
@RestController
class VendorController {

    /** OK: normal. DOWN: payment endpoints answer 503. REJECT: confirm ends in REJECTED. */
    enum Chaos { OK, DOWN, REJECT }

    record CreateDepositRequest(long amount, String currency) {}

    private final Map<String, VendorPayment> byId = new ConcurrentHashMap<>();
    private final AtomicReference<Chaos> chaos = new AtomicReference<>(Chaos.OK);

    // --- vendor API used by the payment app ---

    /**
     * Idempotent by {@code reference}: a payout's id is its reference, so a repeat finds the existing payment.
     */
    @PostMapping(VendorPaths.PAYMENT)
    VendorPayment create(@RequestBody CreatePayoutRequest req) {
        failIfDown();
        return save(req.reference().toString(), req.reference(), VendorPaymentType.PAYOUT,
                VendorPaymentStatus.CREATED, req.amount(), req.currency());
    }

    /** CREATED becomes COMPLETED (or REJECTED in REJECT mode). A terminal payment is returned unchanged. */
    @PostMapping(VendorPaths.PAYMENT_CONFIRM)
    VendorPayment confirm(@PathVariable String id) {
        failIfDown();
        find(id);
        VendorPaymentStatus target = chaos.get() == Chaos.REJECT
                ? VendorPaymentStatus.REJECTED
                : VendorPaymentStatus.COMPLETED;
        return byId.computeIfPresent(id, (key, p) -> p.status() != VendorPaymentStatus.CREATED
                ? p
                : new VendorPayment(p.id(), p.reference(), p.type(), target, p.amount(), p.currency()));
    }

    @GetMapping(VendorPaths.PAYMENT_BY_ID)
    VendorPayment get(@PathVariable String id) {
        failIfDown();
        return find(id);
    }

    // --- admin helpers, not affected by chaos ---

    /** Creates a completed PAYIN that the payment app can then register via {@code POST /deposits}. */
    @PostMapping("/admin/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    VendorPayment createDeposit(@RequestBody CreateDepositRequest req) {
        return save(UUID.randomUUID().toString(), UUID.randomUUID(), VendorPaymentType.PAYIN,
                VendorPaymentStatus.COMPLETED, req.amount(), req.currency());
    }

    @PostMapping("/admin/chaos/{mode}")
    Chaos setChaos(@PathVariable Chaos mode) {
        chaos.set(mode);
        return mode;
    }

    @GetMapping("/admin/chaos")
    Chaos getChaos() {
        return chaos.get();
    }

    /** Stores a new payment under {@code id}, or returns the one already there. */
    private VendorPayment save(String id, UUID reference, VendorPaymentType type, VendorPaymentStatus status,
                               long amount, String currency) {
        return byId.computeIfAbsent(id, k -> new VendorPayment(k, reference, type, status, amount, currency));
    }

    private VendorPayment find(String id) {
        VendorPayment payment = byId.get(id);
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment " + id + " not found");
        }
        return payment;
    }

    private void failIfDown() {
        if (chaos.get() == Chaos.DOWN) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "vendor is down");
        }
    }
}
