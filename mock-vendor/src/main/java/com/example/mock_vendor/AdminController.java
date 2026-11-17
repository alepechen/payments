package com.example.mock_vendor;

import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin helpers for tests and smoke checks. Not affected by chaos.
 */
@RestController
@RequestMapping("/admin")
class AdminController {

    record CreateDepositRequest(long amount, String currency) {}

    private final PaymentStore store;
    private final VendorChaos chaos;

    AdminController(PaymentStore store, VendorChaos chaos) {
        this.store = store;
        this.chaos = chaos;
    }

    /** Creates a completed PAYIN that the payment app can then register via {@code POST /deposits}. */
    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    VendorPayment createDeposit(@RequestBody CreateDepositRequest req) {
        return store.save(UUID.randomUUID().toString(), UUID.randomUUID(), VendorPaymentType.PAYIN,
                VendorPaymentStatus.COMPLETED, req.amount(), req.currency());
    }

    @PostMapping("/chaos/{mode}")
    VendorChaos.Mode setChaos(@PathVariable VendorChaos.Mode mode) {
        chaos.set(mode);
        return mode;
    }

    @GetMapping("/chaos")
    VendorChaos.Mode getChaos() {
        return chaos.get();
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset() {
        store.reset();
        chaos.set(VendorChaos.Mode.OK);
    }
}
