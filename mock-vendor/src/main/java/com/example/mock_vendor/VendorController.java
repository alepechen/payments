package com.example.mock_vendor;

import com.example.vendor.api.CreatePayoutRequest;
import com.example.vendor.api.VendorPaths;
import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fake vendor API used by the payment app. State lives in {@link PaymentStore}.
 */
@RestController
class VendorController {

    private final PaymentStore store;
    private final VendorChaos chaos;

    VendorController(PaymentStore store, VendorChaos chaos) {
        this.store = store;
        this.chaos = chaos;
    }

    /**
     * Idempotent by {@code reference}: a repeat finds the existing payment.
     */
    @PostMapping(VendorPaths.PAYMENT)
    VendorPayment create(@RequestBody CreatePayoutRequest req) {
        chaos.failIfDown();
        validate(req);
        VendorPayment p = store.createPayoutIfAbsent(req.reference(), req.amount(), req.currency());
        if (p.amount() != req.amount() || !p.currency().equals(req.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "reference " + req.reference() + " reused with different parameters");
        }
        chaos.failIfLostResponse();   // effect applied, caller never learns it
        return p;
    }

    /** CREATED becomes COMPLETED (or REJECTED in REJECT mode). A terminal payment is returned unchanged. */
    @PostMapping(VendorPaths.PAYMENT_CONFIRM)
    VendorPayment confirm(@PathVariable String id) {
        chaos.failIfDown();
        find(id);
        VendorPaymentStatus target = chaos.get() == VendorChaos.Mode.REJECT
                ? VendorPaymentStatus.REJECTED
                : VendorPaymentStatus.COMPLETED;
        VendorPayment result = store.complete(id, target);
        chaos.failIfLostResponse();
        return result;
    }

    @GetMapping(VendorPaths.PAYMENT_BY_ID)
    VendorPayment get(@PathVariable String id) {
        chaos.failIfDown();
        return find(id);
    }

    private VendorPayment find(String id) {
        return store.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "payment " + id + " not found"));
    }

    private void validate(CreatePayoutRequest req) {
        if (req.reference() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reference is required");
        }
        if (req.amount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
    }
}
