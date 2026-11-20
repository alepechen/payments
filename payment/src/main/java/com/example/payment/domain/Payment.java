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
        this.nextAttemptAt = this.updatedAt;   // every status change makes the next step due immediately
    }

    /** CREATED -> HELD: funds reserved; the next step starts with a clean attempt count. */
    public void markHeld() {
        transitionTo(WithdrawalStatus.HELD);
        this.attempts = 0;
        this.lastError = null;
    }

    /** HELD -> INITIATED: the vendor accepted the payout. */
    public void markInitiated(String vendorPaymentId) {
        transitionTo(WithdrawalStatus.INITIATED);
        this.vendorPaymentId = vendorPaymentId;
        this.attempts = 0;
        this.lastError = null;
    }

    /** CREATED -> DECLINED without touching money (no hold exists yet). */
    public void decline(String reason) {
        transitionTo(WithdrawalStatus.DECLINED);
        this.lastError = reason;
    }

    /** A failed attempt: counts toward the limit and waits until {@code next}. */
    public void recordRetry(String error, Instant next) {
        this.attempts++;
        this.lastError = error;
        this.nextAttemptAt = next;
        this.updatedAt = Instant.now();
    }

    /** We didn't get to try (throttled): wait until {@code next}, no attempt counted. */
    public void deferUntil(Instant next, String reason) {
        this.lastError = reason;
        this.nextAttemptAt = next;
        this.updatedAt = Instant.now();
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getVendorPaymentId() {
        return vendorPaymentId;
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
