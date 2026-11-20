package com.example.payment.worker;

import com.example.payment.domain.Account;
import com.example.payment.domain.LedgerEntry;
import com.example.payment.domain.LedgerEntryType;
import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.AccountQueryService;
import com.example.payment.service.AccountService;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the handler directly: each call is one attempt, without waiting for nextAttemptAt.
 * Rolled back after each test, so the seeded account is untouched for other test classes.
 */
@SpringBootTest
@Transactional
@Import(FakeVendorGateway.Config.class)
class HeldStatusHandlerTest {

    @Autowired HeldStatusHandler handler;
    @Autowired FakeVendorGateway vendor;
    @Autowired AccountService accountService;
    @Autowired AccountQueryService accounts;
    @Autowired PaymentRepository payments;
    @Autowired LedgerEntryRepository ledger;

    Account account;
    Payment withdrawal;

    @BeforeEach
    void depositThousandAndHoldHundred() {
        vendor.reset();
        account = accounts.current();
        Payment deposit = payments.save(Payment.newDeposit(account, 1000, "USD", "vendor-" + UUID.randomUUID()));
        accountService.deposit(deposit.getId());

        withdrawal = payments.save(Payment.newWithdrawal(account, 100, "USD", "key-" + UUID.randomUUID()));
        accountService.hold(withdrawal.getId());
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.HELD);
    }

    @Test
    void vendorAcceptsMovesToInitiated() {
        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(vendor.createCalls()).containsExactly(withdrawal.getId());   // reference = payment id
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
        assertThat(withdrawal.getVendorPaymentId()).isEqualTo(FakeVendorGateway.vendorIdFor(withdrawal.getId()));
        assertThat(withdrawal.getAttempts()).isZero();
        assertThat(account.getHeld()).isEqualTo(100);                           // still held until settle
        assertThat(entriesFor(withdrawal)).containsExactly(HOLD);
    }

    @Test
    void vendorRejectsReleasesAndDeclines() {
        vendor.onCreate(new VendorResult.Rejected<>(422, "invalid account"));

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(1000);
        assertThat(entriesFor(withdrawal)).containsExactlyInAnyOrder(HOLD, RELEASE);
    }

    @Test
    void vendorDownOnceSchedulesRetryAndKeepsHold() {
        vendor.onCreate(new VendorResult.Retryable<>("503 Service Unavailable"));
        Instant before = Instant.now();

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(withdrawal.getAttempts()).isEqualTo(1);
        assertThat(withdrawal.getLastError()).isEqualTo("503 Service Unavailable");
        assertThat(withdrawal.getNextAttemptAt()).isAfter(before);              // backed off
        assertThat(account.getHeld()).isEqualTo(100);
        assertThat(entriesFor(withdrawal)).containsExactly(HOLD);
    }

    @Test
    void vendorDownFiveTimesReleasesAndDeclines() {
        vendor.onCreate(new VendorResult.Retryable<>("503 Service Unavailable"), 5);

        for (int i = 1; i <= 4; i++) {
            handler.handle(withdrawal);
            assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.HELD);
            assertThat(withdrawal.getAttempts()).isEqualTo(i);
        }
        handler.handle(withdrawal);                                             // 5th failure: out of attempts

        assertThat(vendor.createCalls()).hasSize(5);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(1000);
        assertThat(entriesFor(withdrawal)).containsExactlyInAnyOrder(HOLD, RELEASE);
    }

    @Test
    void vendorDownThenBackUpMovesToInitiatedWithFreshAttempts() {
        vendor.onCreate(new VendorResult.Retryable<>("503 Service Unavailable"), 3);

        for (int i = 0; i < 4; i++) handler.handle(withdrawal);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
        assertThat(withdrawal.getAttempts()).isZero();                          // confirm gets its own budget
        assertThat(withdrawal.getLastError()).isNull();
        assertThat(account.getHeld()).isEqualTo(100);
        assertThat(entriesFor(withdrawal)).containsExactly(HOLD);
    }

    @Test
    void throttledDefersWithoutCountingAnAttempt() {
        Duration retryAfter = Duration.ofSeconds(30);
        vendor.onCreate(new VendorResult.Throttled<>(retryAfter, "429 Too Many Requests"));
        Instant before = Instant.now();

        assertThat(handler.handle(withdrawal)).isEqualTo(new StepResult.Throttled(retryAfter));

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(withdrawal.getAttempts()).isZero();
        assertThat(withdrawal.getLastError()).isEqualTo("429 Too Many Requests");
        assertThat(withdrawal.getNextAttemptAt()).isAfterOrEqualTo(before.plus(retryAfter));
        assertThat(account.getHeld()).isEqualTo(100);
    }

    private List<LedgerEntryType> entriesFor(Payment p) {
        return ledger.findAll().stream()
                .filter(e -> e.getPayment().getId().equals(p.getId()))
                .map(LedgerEntry::getEntryType)
                .toList();
    }
}
