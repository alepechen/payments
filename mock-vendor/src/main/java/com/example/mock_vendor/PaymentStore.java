package com.example.mock_vendor;

import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * In-memory payment storage, indexed by id and by reference. Lost on restart.
 */
@Component
class PaymentStore {

    private record State(Map<String, VendorPayment> byId, Map<UUID, String> idByReference) {
        State() {
            this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private final AtomicReference<State> state = new AtomicReference<>(new State());

    /**
     * Returns the payout for {@code reference}, creating it (status CREATED) if absent.
     * A payout's id is derived from its reference, so a repeat finds the existing payment.
     */
    VendorPayment createPayoutIfAbsent(UUID reference, long amount, String currency) {
        State s = state.get();
        String id = s.idByReference().computeIfAbsent(reference, ref -> {
            String newId = "pay_" + UUID.randomUUID();
            s.byId().put(newId, new VendorPayment(newId, ref, VendorPaymentType.PAYOUT,
                    VendorPaymentStatus.CREATED, amount, currency));
            return newId;
        });
        return s.byId().get(id);
    }

    /** Stores a new payment under {@code id}, or returns the one already there. */
    VendorPayment save(String id, UUID reference, VendorPaymentType type, VendorPaymentStatus status,
                       long amount, String currency) {
        return state.get().byId().computeIfAbsent(id,
                k -> new VendorPayment(k, reference, type, status, amount, currency));
    }

    Optional<VendorPayment> find(String id) {
        return Optional.ofNullable(state.get().byId().get(id));
    }

    /** Moves a CREATED payment to {@code target}; a payment in any other status is returned unchanged. */
    VendorPayment complete(String id, VendorPaymentStatus target) {
        return state.get().byId().computeIfPresent(id, (k, p) ->
                p.status() != VendorPaymentStatus.CREATED
                        ? p
                        : new VendorPayment(p.id(), p.reference(), p.type(), target, p.amount(), p.currency()));
    }

    void reset() {
        state.set(new State());
    }
}
