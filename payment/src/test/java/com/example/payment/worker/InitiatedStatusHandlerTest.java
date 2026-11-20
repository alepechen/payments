package com.example.payment.worker;

import com.example.payment.domain.Account;
import com.example.payment.domain.LedgerEntry;
import com.example.payment.domain.LedgerEntryType;
import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.repository.AccountRepository;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.AccountService;
import com.example.payment.service.WithdrawalService;
import com.example.payment.vendor.FakeVendorGateway;
import com.example.payment.vendor.VendorResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.example.payment.domain.LedgerEntryType.HOLD;
import static com.example.payment.domain.LedgerEntryType.RELEASE;
import static com.example.payment.domain.LedgerEntryType.SETTLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the handler directly: each call is one attempt, without waiting for nextAttemptAt.
 * Rolled back after each test. Uses its own fresh account, so committed money from other test
 * classes (deposits) can't change the numbers here.
 */
@SpringBootTest
@Transactional
@Import(FakeVendorGateway.Config.class)
class InitiatedStatusHandlerTest {

    @Autowired InitiatedStatusHandler handler;
    @Autowired FakeVendorGateway vendor;
    @Autowired AccountService accountService;
    @Autowired WithdrawalService withdrawalService;
    @Autowired AccountRepository accountRepository;
    @Autowired PaymentRepository payments;
    @Autowired LedgerEntryRepository ledger;

    Account account;
    Payment withdrawal;
    String vendorPaymentId;

    @BeforeEach
    void depositThousandAndInitiateHundred() {
        vendor.reset();
        account = accountRepository.save(Account.open("test-" + UUID.randomUUID(), "USD"));   // fresh, isolated
        Payment deposit = payments.save(Payment.newDeposit(account, 1000, "USD", "vendor-" + UUID.randomUUID()));
        accountService.deposit(deposit.getId());

        withdrawal = payments.save(Payment.newWithdrawal(account, 100, "USD", "key-" + UUID.randomUUID()));
        accountService.hold(withdrawal.getId());
        vendorPaymentId = FakeVendorGateway.vendorIdFor(withdrawal.getId());
        withdrawalService.markInitiated(withdrawal.getId(), vendorPaymentId);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
    }

    @Test
    void vendorConfirmsSettlesAndCompletes() {
        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(vendor.confirmCalls()).containsExactly(vendorPaymentId);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(account.getBalance()).isEqualTo(900);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(900);
        assertThat(entriesFor(withdrawal)).containsExactlyInAnyOrder(HOLD, SETTLE);
    }

    @Test
    void vendorRejectsReleasesAndDeclines() {
        vendor.onConfirm(new VendorResult.Rejected<>(422, "invalid account"));

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(account.getBalance()).isEqualTo(1000);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(1000);
        assertThat(entriesFor(withdrawal)).containsExactlyInAnyOrder(HOLD, RELEASE);
    }

    @Test
    void vendorDownTwentyTimesThenBackUpCompletes() {
        vendor.onConfirm(new VendorResult.Retryable<>("503 Service Unavailable"), 20);

        for (int i = 1; i <= 20; i++) {
            handler.handle(withdrawal);
            assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);   // never gives up
            assertThat(withdrawal.getAttempts()).isEqualTo(i);
            assertThat(account.getHeld()).isEqualTo(100);                               // hold kept throughout
        }
        handler.handle(withdrawal);                                                     // vendor back up

        assertThat(vendor.confirmCalls()).hasSize(21).containsOnly(vendorPaymentId);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(account.getBalance()).isEqualTo(900);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(900);
        assertThat(entriesFor(withdrawal)).containsExactlyInAnyOrder(HOLD, SETTLE);    // no RELEASE ever
    }
    @Test
    void vendorThrottledDefersWithoutCountingAnAttempt() {
        Duration retryAfter = Duration.ofSeconds(30);
        vendor.onConfirm(new VendorResult.Throttled<>(retryAfter, "429 Too Many Requests"));
        Instant before = Instant.now();

        assertThat(handler.handle(withdrawal)).isEqualTo(new StepResult.Throttled(retryAfter));

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
        assertThat(withdrawal.getAttempts()).isZero();
        assertThat(withdrawal.getLastError()).isEqualTo("429 Too Many Requests");
        assertThat(withdrawal.getNextAttemptAt()).isAfterOrEqualTo(before.plus(retryAfter));
        assertThat(account.getBalance()).isEqualTo(1000);
        assertThat(account.getHeld()).isEqualTo(100);                                   // hold kept
        assertThat(account.available()).isEqualTo(900);
        assertThat(entriesFor(withdrawal)).containsExactly(HOLD);
    }

    @Test
    void vendorRejectsAfterRetriesReleasesAndDeclines() {
        vendor.onConfirm(new VendorResult.Retryable<>("503 Service Unavailable"), 5);

        for (int i = 1; i <= 5; i++) {
            handler.handle(withdrawal);
            assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
            assertThat(withdrawal.getAttempts()).isEqualTo(i);
            assertThat(account.getHeld()).isEqualTo(100);
        }
        vendor.onConfirm(new VendorResult.Rejected<>(422, "invalid account"));

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(account.getBalance()).isEqualTo(1000);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(1000);
        assertThat(entriesFor(withdrawal)).containsExactlyInAnyOrder(HOLD, RELEASE);
    }

    /**
     * After the n-th failure the next attempt is due in min(2^n s, 10 min) plus up to 1s of jitter:
     * 2s, 4s, 8s, ... 512s, then capped at 600s from the 10th failure on.
     */
    @Test
    void backOffGrowsExponentiallyAndIsCappedAtTenMinutes() {
        vendor.onConfirm(new VendorResult.Retryable<>("503 Service Unavailable"), 15);

        for (int n = 1; n <= 15; n++) {
            Instant before = Instant.now();
            handler.handle(withdrawal);
            Instant after = Instant.now();

            Duration base = min(Duration.ofSeconds(1L << n), Duration.ofMinutes(10));
            assertThat(withdrawal.getAttempts()).isEqualTo(n);
            assertThat(withdrawal.getNextAttemptAt())
                    .as("next attempt after failure %d", n)
                    .isBetween(before.plus(base), after.plus(base).plusSeconds(1));
        }
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);       // still never gives up
        assertThat(account.getHeld()).isEqualTo(100);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private List<LedgerEntryType> entriesFor(Payment p) {
        return ledger.findByPaymentId(p.getId()).stream()
                .map(LedgerEntry::getEntryType)
                .toList();
    }
}
