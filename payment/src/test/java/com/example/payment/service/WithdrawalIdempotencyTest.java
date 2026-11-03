package com.example.payment.service;

import com.example.payment.dto.CreateResult;
import com.example.payment.dto.CreateWithdrawalRequest;
import com.example.payment.exception.IdempotencyConflictException;
import com.example.payment.repository.PaymentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Not @Transactional: the concurrent test needs real, separate transactions. Uses unique keys instead of cleanup. */
@SpringBootTest
class WithdrawalIdempotencyTest {

    @Autowired WithdrawalService withdrawals;
    @Autowired PaymentRepository payments;

    @Test
    void sameKeyTwiceReturnsSameWithdrawal() {
        var request = request(100);

        CreateResult first = withdrawals.create(request);
        CreateResult second = withdrawals.create(request);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(rowsWithKey(request.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentCurrencyIsConflict() {
        var request = request(100);
        withdrawals.create(request);

        var different = new CreateWithdrawalRequest(request.idempotencyKey(), 100, "EUR");
        assertThatThrownBy(() -> withdrawals.create(different))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(rowsWithKey(request.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentAmountIsConflict() {
        var request = request(100);
        withdrawals.create(request);

        var different = new CreateWithdrawalRequest(request.idempotencyKey(), 200, "USD");
        assertThatThrownBy(() -> withdrawals.create(different))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneWithdrawal() throws Exception {
        var request = request(100);
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
                    return withdrawals.create(request);
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
            assertThat(rowsWithKey(request.idempotencyKey())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyIdempotencyConstraintCountsAsReplay() {
        var idempotency = violation("PUBLIC.UQ_CLIENT_IDEMPOTENCY_KEY_INDEX_3 ON PUBLIC.PAYMENT(CLIENT_IDEMPOTENCY_KEY)");
        var other = violation("CONSTRAINT_1");
        var unnamed = violation(null);

        assertThat(Constraints.isViolationOf(idempotency, Constraints.IDEMPOTENCY_KEY)).isTrue();
        assertThat(Constraints.isViolationOf(other, Constraints.IDEMPOTENCY_KEY)).isFalse();
        assertThat(Constraints.isViolationOf(unnamed, Constraints.IDEMPOTENCY_KEY)).isFalse();
    }

    /** Mirrors what Spring throws: a DataIntegrityViolationException wrapping Hibernate's ConstraintViolationException. */
    private static DataIntegrityViolationException violation(String constraintName) {
        var cause = new ConstraintViolationException(
                "could not execute statement", new SQLException("violation"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    private long rowsWithKey(String key) {
        return payments.findAll().stream().filter(p -> key.equals(p.getClientIdempotencyKey())).count();
    }

    private static CreateWithdrawalRequest request(long amount) {
        return new CreateWithdrawalRequest("key-" + UUID.randomUUID(), amount, "USD");
    }
}
