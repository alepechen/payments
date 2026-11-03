package com.example.payment.service;

import com.example.payment.domain.*;
import com.example.payment.repository.AccountRepository;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentRepository paymentRepository;

    public AccountService(PaymentRepository paymentRepository, AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public void deposit(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        Account account = payment.getAccount();

        account.credit(payment.getAmount());
        ledgerEntryRepository.save(
                LedgerEntry.of(account, payment, LedgerEntryType.DEPOSIT, payment.getAmount()));
    }

    @Transactional
    public void hold(UUID paymentId) {
        Payment payment = loadWithdrawal(paymentId);
        Account account = payment.getAccount();

        account.hold(payment.getAmount());               // checks available(), throws InsufficientFundsException
        payment.transitionTo(WithdrawalStatus.HELD);     // throws IllegalStateTransitionException if not CREATED
        ledgerEntryRepository.save(
                LedgerEntry.of(account, payment, LedgerEntryType.HOLD, payment.getAmount()));
    }

    /** INITIATED -> COMPLETED: money has left; remove it from balance and held. */
    @Transactional
    public void settle(UUID paymentId) {
        Payment payment = loadWithdrawal(paymentId);
        Account account = payment.getAccount();

        account.settle(payment.getAmount());
        payment.transitionTo(WithdrawalStatus.COMPLETED);
        ledgerEntryRepository.save(
                LedgerEntry.of(account, payment, LedgerEntryType.SETTLE, payment.getAmount()));
    }

    /** HELD or INITIATED -> DECLINED: give the reserved funds back. */
    @Transactional
    public void release(UUID paymentId) {
        Payment payment = loadWithdrawal(paymentId);
        Account account = payment.getAccount();

        account.release(payment.getAmount());
        payment.transitionTo(WithdrawalStatus.DECLINED);
        ledgerEntryRepository.save(
                LedgerEntry.of(account, payment, LedgerEntryType.RELEASE, payment.getAmount()));
    }

    private Payment loadWithdrawal(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment %s not found".formatted(paymentId)));
    }
}
