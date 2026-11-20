package com.example.payment.worker;

import com.example.payment.domain.LedgerEntry;
import com.example.payment.domain.LedgerEntryType;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentType;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.dto.CreateWithdrawalRequest;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.AccountQueryService;
import com.example.payment.service.DepositService;
import com.example.payment.service.WithdrawalService;
import com.example.payment.vendor.FakeVendorGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.payment.domain.LedgerEntryType.DEPOSIT;
import static com.example.payment.domain.LedgerEntryType.HOLD;
import static com.example.payment.domain.LedgerEntryType.SETTLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * End to end through the worker, with the scheduler off and ticks driven by hand.
 * Not @Transactional: every step commits in its own transaction, like in production. Other test
 * classes share the in-memory DB, so leftovers are drained first and balances are compared as deltas.
 */
@SpringBootTest
@Import(FakeVendorGateway.Config.class)
class PayoutWorkerTest {

    private static final Set<WithdrawalStatus> IN_FLIGHT =
            Set.of(WithdrawalStatus.CREATED, WithdrawalStatus.HELD, WithdrawalStatus.INITIATED);

    @Autowired PayoutWorker worker;
    @Autowired FakeVendorGateway vendor;
    @Autowired DepositService deposits;
    @MockitoSpyBean WithdrawalService withdrawals;     // real, except where a test stubs it
    @Autowired AccountQueryService accounts;
    @Autowired PaymentRepository payments;
    @Autowired LedgerEntryRepository ledger;

    @BeforeEach
    void setUp() {
        vendor.reset();
        drainDueWithdrawals();
        vendor.reset();
    }

    @Test
    void depositThenWithdrawalReachesCompletedOneStepPerTick() {
        long balanceBefore = accounts.current().getBalance();
        long heldBefore = accounts.current().getHeld();

        // deposit 1000
        String payinId = "payin-" + UUID.randomUUID();
        vendor.givenPayin(payinId, 1000, "USD");
        Payment deposit = deposits.register(payinId).payment();

        // withdraw 100
        UUID id = withdrawals.create(
                new CreateWithdrawalRequest("key-" + UUID.randomUUID(), 100, "USD")).payment().getId();
        assertThat(reload(id).getStatus()).isEqualTo(WithdrawalStatus.CREATED);

        worker.tick();                                                  // CREATED -> HELD
        assertThat(reload(id).getStatus()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(accounts.current().getHeld()).isEqualTo(heldBefore + 100);
        assertThat(vendor.createCalls()).isEmpty();                    // holding needs no vendor call

        worker.tick();                                                  // HELD -> INITIATED
        Payment initiated = reload(id);
        assertThat(initiated.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
        assertThat(initiated.getVendorPaymentId()).isEqualTo(FakeVendorGateway.vendorIdFor(id));
        assertThat(vendor.createCalls()).containsExactly(id);           // reference = payment id

        worker.tick();                                                  // INITIATED -> COMPLETED
        Payment completed = reload(id);
        assertThat(completed.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(vendor.confirmCalls()).containsExactly(FakeVendorGateway.vendorIdFor(id));

        // balance 900 (relative to what other tests left), nothing held, available 900
        assertThat(accounts.current().getBalance()).isEqualTo(balanceBefore + 900);
        assertThat(accounts.current().getHeld()).isEqualTo(heldBefore);
        assertThat(entriesFor(deposit.getId())).containsExactly(DEPOSIT);
        assertThat(entriesFor(id)).containsExactlyInAnyOrder(HOLD, SETTLE);

        worker.tick();                                                  // terminal: nothing more happens
        assertThat(vendor.createCalls()).hasSize(1);
        assertThat(vendor.confirmCalls()).hasSize(1);
    }

    /**
     * Crash between the vendor call and the DB save: the vendor created the payout, but we never
     * stored INITIATED. What the worker must do: retry createPayout with the same reference (so the
     * vendor, idempotent by reference, can return the existing payout), save it, and leave the money
     * alone. That the vendor really keeps one payout per reference is the vendor's contract, tested
     * against mock-vendor, not here.
     */
    @Test
    void crashAfterCreatePayoutIsRecoveredOnNextTick() {
        String payinId = "payin-" + UUID.randomUUID();
        vendor.givenPayin(payinId, 1000, "USD");
        deposits.register(payinId);
        UUID id = withdrawals.create(
                new CreateWithdrawalRequest("key-" + UUID.randomUUID(), 100, "USD")).payment().getId();
        long heldBefore = accounts.current().getHeld();

        worker.tick();                                                  // CREATED -> HELD
        assertThat(reload(id).getStatus()).isEqualTo(WithdrawalStatus.HELD);

        // the save after the vendor call dies once; a crashed process records nothing either
        doThrow(new IllegalStateException("simulated crash")).doCallRealMethod()
                .when(withdrawals).markInitiated(any(), any());
        doNothing().when(withdrawals).recordFailure(any(), any());

        worker.tick();                                                  // vendor OK, save fails
        assertThat(reload(id).getStatus()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(reload(id).getVendorPaymentId()).isNull();
        assertThat(vendor.createCalls()).containsExactly(id);

        worker.tick();                                                  // same reference again -> saved
        Payment recovered = reload(id);
        assertThat(recovered.getStatus()).isEqualTo(WithdrawalStatus.INITIATED);
        assertThat(recovered.getVendorPaymentId()).isEqualTo(FakeVendorGateway.vendorIdFor(id));
        assertThat(vendor.createCalls()).containsExactly(id, id);       // retried with the same reference
        assertThat(accounts.current().getHeld()).isEqualTo(heldBefore + 100);   // held once, not released
        assertThat(entriesFor(id)).containsExactly(HOLD);
    }

    /** Runs leftovers from other test classes to a terminal state, so ticks below touch only our payment. */
    private void drainDueWithdrawals() {
        for (int i = 0; i < 100 && !due().isEmpty(); i++) {
            worker.tick();
        }
        assertThat(due()).as("withdrawals left over by other tests").isEmpty();
    }

    private List<Payment> due() {
        return payments.findTop10ByTypeAndStatusInAndNextAttemptAtBeforeOrderByNextAttemptAt(
                PaymentType.WITHDRAWAL, IN_FLIGHT, Instant.now());
    }

    private Payment reload(UUID id) {
        return payments.findById(id).orElseThrow();
    }

    private List<LedgerEntryType> entriesFor(UUID paymentId) {
        return ledger.findByPaymentId(paymentId).stream()
                .map(LedgerEntry::getEntryType)
                .toList();
    }
}
