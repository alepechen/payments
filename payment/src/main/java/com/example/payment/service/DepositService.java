package com.example.payment.service;

import com.example.payment.domain.Account;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentType;
import com.example.payment.dto.CreateResult;
import com.example.payment.exception.InvalidDepositException;
import com.example.payment.exception.VendorUnavailableException;
import com.example.payment.repository.AccountRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.vendor.VendorGateway;
import com.example.payment.vendor.VendorResult;
import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final PaymentRepository payments;
    private final AccountQueryService accounts;
    private final VendorGateway vendor;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransactionTemplate newTx;

    public DepositService(PaymentRepository payments, AccountQueryService accounts, VendorGateway vendor,
                          AccountRepository accountRepository, AccountService accountService,
                          PlatformTransactionManager txManager) {
        this.payments = payments;
        this.accounts = accounts;
        this.vendor = vendor;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Credits a completed vendor payin exactly once.
     */
    public CreateResult register(String vendorPaymentId) {
        // 1. Fast path: already registered, no vendor call needed
        Optional<Payment> existing = payments.findByVendorPaymentId(vendorPaymentId);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        // 2. Ask the vendor
        VendorPayment payin = switch (vendor.get(vendorPaymentId)) {
            case VendorResult.Success<VendorPayment> s -> s.value();
            case VendorResult.Rejected<VendorPayment> r ->
                    throw new InvalidDepositException("Vendor payment %s not found".formatted(vendorPaymentId));
            case VendorResult.Retryable<VendorPayment> r ->
                    throw new VendorUnavailableException("Vendor unavailable: " + r.reason(), null);
            case VendorResult.Throttled<VendorPayment> t ->
                    throw new VendorUnavailableException("Vendor busy: " + t.reason(), t.retryAfter());
        };

        // 3. Is it something we may credit?
        Account account = accounts.current();
        validate(vendorPaymentId, payin, account);

        try {
            // 4. Insert + credit in one new transaction; saveAndFlush makes the unique constraint fire here
            Payment deposit = newTx.execute(tx -> {
                Payment p = payments.saveAndFlush(Payment.newDeposit(
                        accountRepository.getReferenceById(account.getId()), payin.amount(), payin.currency(), payin.id()));
                accountService.deposit(p.getId());                                   // joins this transaction
                return p;
            });
            log.info("payment={} deposit {} {} registered from vendor payment {}",
                    deposit.getId(), payin.amount(), payin.currency(), vendorPaymentId);
            return new CreateResult(deposit, true);
        } catch (DataIntegrityViolationException e) {
            // 5. Lost the race on uq_vendor_payment_id: the insert's transaction has rolled back, so this read is a
            //    fresh one. No row with our key means it was some other violation (FK, NOT NULL, CHECK):
            //    orElseThrow rethrows it instead of disguising it as a replay.
            Payment winner = payments.findByVendorPaymentId(vendorPaymentId).orElseThrow(() -> e);
            return replay(winner);
        }
    }

    private static void validate(String vendorPaymentId, VendorPayment payin, Account account) {
        if (payin.type() != VendorPaymentType.PAYIN) {
            throw new InvalidDepositException("Vendor payment %s is a %s, not a payin".formatted(vendorPaymentId, payin.type()));
        }
        if (payin.status() != VendorPaymentStatus.COMPLETED) {
            throw new InvalidDepositException("Vendor payment %s is %s, not COMPLETED".formatted(vendorPaymentId, payin.status()));
        }
        if (!account.getCurrency().equals(payin.currency())) {
            throw new InvalidDepositException("Vendor payment %s is in %s, account is in %s"
                    .formatted(vendorPaymentId, payin.currency(), account.getCurrency()));
        }
        if (payin.amount() <= 0) {
            throw new InvalidDepositException("Vendor payment %s has amount %d".formatted(vendorPaymentId, payin.amount()));
        }
    }

    private static CreateResult replay(Payment existing) {
        if (existing.getType() != PaymentType.DEPOSIT) {
            throw new InvalidDepositException("Vendor payment %s belongs to a withdrawal".formatted(existing.getVendorPaymentId()));
        }
        return new CreateResult(existing, false);
    }
}
