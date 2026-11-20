package com.example.payment.service;

import com.example.payment.domain.LedgerEntry;
import com.example.payment.domain.LedgerEntryType;
import com.example.payment.domain.PaymentType;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.dto.CreateResult;
import com.example.payment.exception.InvalidDepositException;
import com.example.payment.exception.VendorUnavailableException;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.vendor.FakeVendorGateway;
import com.example.payment.vendor.VendorResult;
import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import com.example.vendor.api.VendorPaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Not @Transactional: the concurrent test needs real, separate transactions. Uses unique vendor ids
 * instead of cleanup, and compares balances before/after because other tests share the account.
 */
@SpringBootTest
@Import(FakeVendorGateway.Config.class)
class DepositServiceTest {

    @Autowired DepositService deposits;
    @Autowired FakeVendorGateway vendor;
    @Autowired AccountQueryService accounts;
    @Autowired PaymentRepository payments;
    @Autowired LedgerEntryRepository ledger;

    String vendorId;

    @BeforeEach
    void setUp() {
        vendor.reset();
        vendorId = "payin-" + UUID.randomUUID();
    }

    @Test
    void completedPayinIsCredited() {
        vendor.givenPayin(vendorId, 1000, "USD");
        long before = balance();

        CreateResult result = deposits.register(vendorId);

        assertThat(result.created()).isTrue();
        assertThat(result.payment().getType()).isEqualTo(PaymentType.DEPOSIT);
        assertThat(result.payment().getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(result.payment().getAmount()).isEqualTo(1000);
        assertThat(balance()).isEqualTo(before + 1000);
        assertThat(entriesFor(vendorId)).containsExactly(LedgerEntryType.DEPOSIT);
    }

    @Test
    void sameVendorIdTwiceIsCreditedOnce() {
        vendor.givenPayin(vendorId, 1000, "USD");
        long before = balance();

        CreateResult first = deposits.register(vendorId);
        CreateResult second = deposits.register(vendorId);

        assertThat(second.created()).isFalse();
        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(vendor.getCalls()).hasSize(1);                         // repeat served from the DB
        assertThat(balance()).isEqualTo(before + 1000);
        assertThat(entriesFor(vendorId)).containsExactly(LedgerEntryType.DEPOSIT);
    }

    @Test
    void concurrentRegistrationsCreditOnce() throws Exception {
        vendor.givenPayin(vendorId, 1000, "USD");
        long before = balance();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<CreateResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return deposits.register(vendorId);
                }));
            }
            ready.await();
            go.countDown();

            List<CreateResult> results = new ArrayList<>();
            for (Future<CreateResult> f : futures) {
                results.add(f.get());
            }

            assertThat(results).extracting(r -> r.payment().getId()).containsOnly(results.get(0).payment().getId());
            assertThat(results).filteredOn(CreateResult::created).hasSize(1);
            assertThat(balance()).isEqualTo(before + 1000);
            assertThat(entriesFor(vendorId)).containsExactly(LedgerEntryType.DEPOSIT);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void payoutIsNotADeposit() {
        vendor.given(new VendorPayment(vendorId, UUID.randomUUID(), VendorPaymentType.PAYOUT,
                VendorPaymentStatus.COMPLETED, 1000, "USD"));

        assertRejectedAndNothingWritten("not a payin");
    }

    @Test
    void payinNotCompletedIsRejected() {
        vendor.given(new VendorPayment(vendorId, null, VendorPaymentType.PAYIN,
                VendorPaymentStatus.CREATED, 1000, "USD"));

        assertRejectedAndNothingWritten("not COMPLETED");
    }

    @Test
    void payinInOtherCurrencyIsRejected() {
        vendor.givenPayin(vendorId, 1000, "EUR");

        assertRejectedAndNothingWritten("account is in USD");
    }

    @Test
    void unknownVendorPaymentIsRejected() {
        assertRejectedAndNothingWritten("not found");                    // fake returns Rejected(404)
    }

    @Test
    void vendorDownIsUnavailable() {
        vendor.onGet(new VendorResult.Retryable<>("503 Service Unavailable"));

        assertThatThrownBy(() -> deposits.register(vendorId))
                .isInstanceOf(VendorUnavailableException.class)
                .extracting(e -> ((VendorUnavailableException) e).retryAfter()).isNull();
        assertThat(payments.findByVendorPaymentId(vendorId)).isEmpty();
    }

    @Test
    void vendorThrottledIsUnavailableWithRetryAfter() {
        vendor.onGet(new VendorResult.Throttled<>(Duration.ofSeconds(30), "429 Too Many Requests"));

        assertThatThrownBy(() -> deposits.register(vendorId))
                .isInstanceOf(VendorUnavailableException.class)
                .extracting(e -> ((VendorUnavailableException) e).retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(payments.findByVendorPaymentId(vendorId)).isEmpty();
    }

    private void assertRejectedAndNothingWritten(String messagePart) {
        long before = balance();

        assertThatThrownBy(() -> deposits.register(vendorId))
                .isInstanceOf(InvalidDepositException.class)
                .hasMessageContaining(messagePart);

        assertThat(payments.findByVendorPaymentId(vendorId)).isEmpty();
        assertThat(balance()).isEqualTo(before);
    }

    private long balance() {
        return accounts.current().getBalance();
    }

    private List<LedgerEntryType> entriesFor(String vendorPaymentId) {
        UUID paymentId = payments.findByVendorPaymentId(vendorPaymentId).orElseThrow().getId();
        return ledger.findByPaymentId(paymentId).stream()
                .map(LedgerEntry::getEntryType)
                .toList();
    }
}
