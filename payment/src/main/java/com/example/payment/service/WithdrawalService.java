package com.example.payment.service;

import com.example.payment.domain.Account;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentType;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.dto.CreateResult;
import com.example.payment.dto.CreateWithdrawalRequest;
import com.example.payment.exception.CurrencyMismatchException;
import com.example.payment.exception.IdempotencyConflictException;
import com.example.payment.exception.NotFoundException;
import com.example.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);

    private final PaymentRepository payments;
    private final AccountQueryService accounts;
    private final WithdrawalWriter writer;

    public WithdrawalService(PaymentRepository payments, AccountQueryService accounts, WithdrawalWriter writer) {
        this.payments = payments;
        this.accounts = accounts;
        this.writer = writer;
    }

    /** Deliberately not {@code @Transactional}: the insert and the re-read each need their own transaction. */
    public CreateResult create(CreateWithdrawalRequest request) {
        String key = request.idempotencyKey();

        // 1. Fast path: replay of an earlier request
        Optional<Payment> existing = payments.findByClientIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }
        Account account = accounts.current();
        if (!account.getCurrency().equals(request.currency())) {
            throw new CurrencyMismatchException(account.getCurrency(), request.currency());
        }

        try {
            // 2. Insert in its own transaction
            return new CreateResult(writer.insert(account.getId(), request), true);
        } catch (DataIntegrityViolationException e) {
            if (!Constraints.isViolationOf(e, Constraints.IDEMPOTENCY_KEY)) {
                throw e;    // a real bug (FK, NOT NULL, CHECK): don't disguise it as a replay
            }
            // 3. Lost the race: the insert's transaction has rolled back, so this read is a fresh one
            Payment winner = payments.findByClientIdempotencyKey(key).orElseThrow(() -> e);
            return replay(winner, request);
        }
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id)
                .filter(p -> p.getType() == PaymentType.WITHDRAWAL)
                .orElseThrow(() -> new NotFoundException("Withdrawal %s not found".formatted(id)));
    }

    private CreateResult replay(Payment existing, CreateWithdrawalRequest request) {
        boolean sameRequest = existing.getType() == PaymentType.WITHDRAWAL
                && existing.getAmount() == request.amount()
                && existing.getCurrency().equals(request.currency());
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency key %s was already used with a different request".formatted(request.idempotencyKey()));
        }
        return new CreateResult(existing, false);
    }

    // --- worker outcomes: one short transaction each, no money moves here (see AccountService) ---

    @Transactional
    public void decline(UUID id, String reason) {
        PaymentGuard.reloadIfStatusIs(payments, id, WithdrawalStatus.CREATED).ifPresent(p -> p.decline(reason));
    }

    @Transactional
    public void markInitiated(UUID id, String vendorPaymentId) {
        PaymentGuard.reloadIfStatusIs(payments, id, WithdrawalStatus.HELD).ifPresent(p -> p.markInitiated(vendorPaymentId));
    }

    /** A failed attempt: attempts++ and back off min(2^attempts x 1s, 10 min) plus jitter. */
    @Transactional
    public void scheduleRetry(UUID id, String error) {
        Optional<Payment> found = payments.findById(id).filter(p -> !p.getStatus().isTerminal());
        found.ifPresent(p -> {
            long seconds = 1L << Math.min(p.getAttempts() + 1, 20);
            Duration delay = Duration.ofSeconds(seconds);
            if (delay.compareTo(MAX_BACKOFF) > 0) delay = MAX_BACKOFF;
            delay = delay.plusMillis(ThreadLocalRandom.current().nextLong(1000));
            p.recordRetry(error, Instant.now().plus(delay));
        });
    }

    /** Throttled: try again after retryAfter, without counting an attempt. */
    @Transactional
    public void deferRetry(UUID id, Duration retryAfter, String reason) {
        payments.findById(id).filter(p -> !p.getStatus().isTerminal())
                .ifPresent(p -> p.deferUntil(Instant.now().plus(retryAfter), reason));
    }

    /**
     * The step itself blew up (a bug or an integration problem): remember why and back off, without
     * counting an attempt. An unexpected error must never lead to a release.
     */
    @Transactional
    public void recordFailure(UUID id, String error) {
        Duration delay = FAILURE_BACKOFF.plusMillis(ThreadLocalRandom.current().nextLong(5000));
        payments.findById(id).filter(p -> !p.getStatus().isTerminal())
                .ifPresent(p -> p.deferUntil(Instant.now().plus(delay), error));
    }
}
