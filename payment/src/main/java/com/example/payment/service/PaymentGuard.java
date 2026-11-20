package com.example.payment.service;

import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/** Reloads a payment by id and checks it is still in the status the worker started from. */
final class PaymentGuard {

    private static final Logger log = LoggerFactory.getLogger(PaymentGuard.class);

    private PaymentGuard() {
    }

    /** Empty (and logged at info) when another actor moved the payment since the worker's snapshot. */
    static Optional<Payment> reloadIfStatusIs(PaymentRepository payments, UUID id, WithdrawalStatus expected) {
        Optional<Payment> p = payments.findById(id).filter(x -> x.getStatus() == expected);
        if (p.isEmpty()) {
            log.info("payment={} is no longer {}, skipping; next tick will look again", id, expected);
        }
        return p;
    }
}
