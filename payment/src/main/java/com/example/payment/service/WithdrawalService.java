package com.example.payment.service;

import com.example.payment.domain.Account;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentType;
import com.example.payment.dto.CreateResult;
import com.example.payment.dto.CreateWithdrawalRequest;
import com.example.payment.exception.CurrencyMismatchException;
import com.example.payment.exception.IdempotencyConflictException;
import com.example.payment.exception.NotFoundException;
import com.example.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class WithdrawalService {

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

}
