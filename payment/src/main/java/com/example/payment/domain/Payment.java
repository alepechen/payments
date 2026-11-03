package com.example.payment.domain;

import com.example.payment.exception.IllegalStateTransitionException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uq_client_idempotency_key", columnNames = "client_idempotency_key"),
        @UniqueConstraint(name = "uq_vendor_payment_id",      columnNames = "vendor_payment_id")
}, indexes = @Index(name = "ix_payment_status_due", columnList = "status, next_attempt_at"))
public class Payment {
    @Id
    UUID id;                                   // generated in code: UUID.randomUUID()
    @ManyToOne(optional = false) Account account;
    @Enumerated(EnumType.STRING) @Column(nullable = false) PaymentType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false) WithdrawalStatus status;
    @Column(nullable = false) long amount;
    @Column(nullable = false, length = 3) String currency;
    @Column(name = "client_idempotency_key") String clientIdempotencyKey;
    @Column(name = "vendor_payment_id") String vendorPaymentId;
    int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    Instant nextAttemptAt;
    String lastError;
    @Column(nullable = false) Instant createdAt;
    Instant updatedAt;
    // Wrapper type on purpose: the id is assigned in code, so a null version is how Spring Data
    // knows the entity is new (persist() instead of merge() with a SELECT first).
    @Version Long version;

    public static Payment newWithdrawal(Account account, long amount, String currency, String idempotencyKey) {
        Instant now = Instant.now();
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.account = account;
        p.type = PaymentType.WITHDRAWAL;
        p.status = WithdrawalStatus.CREATED;
        p.amount = amount;
        p.currency = currency;
        p.clientIdempotencyKey = idempotencyKey;
        p.nextAttemptAt = now;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public static Payment newDeposit(Account account, long amount, String currency, String vendorPaymentId) {
        Instant now = Instant.now();
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.account = account;
        p.type = PaymentType.DEPOSIT;
        p.status = WithdrawalStatus.COMPLETED;
        p.amount = amount;
        p.currency = currency;
        p.vendorPaymentId = vendorPaymentId;
        p.nextAttemptAt = now;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public void transitionTo(WithdrawalStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateTransitionException(id, status, next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public PaymentType getType() {
        return type;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getClientIdempotencyKey() {
        return clientIdempotencyKey;
    }

    public Account getAccount() {
        return account;
    }

    public Long getAmount() {
        return amount;
    }
    public String getCurrency(){
        return currency;
    }
}
