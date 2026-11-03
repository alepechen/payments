package com.example.payment.service;

import com.example.payment.domain.Account;
import com.example.payment.domain.Payment;
import com.example.payment.dto.CreateWithdrawalRequest;
import com.example.payment.repository.AccountRepository;
import com.example.payment.repository.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a withdrawal in its own transaction. This is a separate bean so the proxy applies:
 * a constraint violation rolls back only this transaction, and the caller can still read
 * the winning row afterwards, even if it was itself called inside a transaction.
 */
@Component
class WithdrawalWriter {

    private final PaymentRepository payments;
    private final AccountRepository accounts;

    WithdrawalWriter(PaymentRepository payments,AccountRepository accounts) {
        this.payments = payments;
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment insert(Long accountId, CreateWithdrawalRequest request) {
        Account account = accounts.getReferenceById(accountId);
        return payments.saveAndFlush(Payment.newWithdrawal(
                account, request.amount(), request.currency(), request.idempotencyKey()));
    }
}
