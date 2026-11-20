package com.example.payment.repository;

import com.example.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payment.domain.PaymentType;
import com.example.payment.domain.WithdrawalStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByClientIdempotencyKey(String key);

    Optional<Payment> findByVendorPaymentId(String vendorPaymentId);

    List<Payment> findTop10ByTypeAndStatusInAndNextAttemptAtBeforeOrderByNextAttemptAt(
            PaymentType type, Collection<WithdrawalStatus> statuses, Instant now);
}
