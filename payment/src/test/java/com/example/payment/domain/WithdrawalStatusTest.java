package com.example.payment.domain;

import com.example.payment.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.example.payment.domain.WithdrawalStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WithdrawalStatusTest {

    /** Written out independently of the implementation on purpose. */
    private static final Map<WithdrawalStatus, Set<WithdrawalStatus>> ALLOWED = Map.of(
            CREATED, Set.of(HELD, DECLINED),
            HELD, Set.of(INITIATED, DECLINED),
            INITIATED, Set.of(COMPLETED, DECLINED),
            COMPLETED, Set.of(),
            DECLINED, Set.of());

    @Test
    void everyTransitionIsAllowedOrForbiddenAsExpected() {
        for (WithdrawalStatus from : values()) {
            for (WithdrawalStatus to : values()) {
                assertThat(from.canMoveTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void onlyCompletedAndDeclinedAreTerminal() {
        for (WithdrawalStatus status : values()) {
            assertThat(status.isTerminal())
                    .as(status.name())
                    .isEqualTo(status == COMPLETED || status == DECLINED);
        }
    }

    @Test
    void paymentRejectsForbiddenTransitionsAndKeepsItsStatus() {
        for (WithdrawalStatus from : values()) {
            for (WithdrawalStatus to : values()) {
                if (ALLOWED.get(from).contains(to)) {
                    continue;
                }
                Payment payment = paymentIn(from);
                assertThatThrownBy(() -> payment.transitionTo(to))
                        .as("%s -> %s", from, to)
                        .isInstanceOf(IllegalStateTransitionException.class);
                assertThat(payment.getStatus()).isEqualTo(from);
            }
        }
    }

    @Test
    void paymentAcceptsAllowedTransitions() {
        for (WithdrawalStatus from : values()) {
            for (WithdrawalStatus to : ALLOWED.get(from)) {
                Payment payment = paymentIn(from);
                payment.transitionTo(to);
                assertThat(payment.getStatus()).isEqualTo(to);
            }
        }
    }

    /** Walks the happy path to reach the requested status, since status has no setter. */
    private static Payment paymentIn(WithdrawalStatus target) {
        Payment payment = Payment.newWithdrawal(Account.open("test", "USD"), 100, "USD", "key-12345678");
        switch (target) {
            case CREATED -> { }
            case HELD -> payment.transitionTo(HELD);
            case INITIATED -> { payment.transitionTo(HELD); payment.transitionTo(INITIATED); }
            case COMPLETED -> { payment.transitionTo(HELD); payment.transitionTo(INITIATED); payment.transitionTo(COMPLETED); }
            case DECLINED -> payment.transitionTo(DECLINED);
        }
        return payment;
    }
}
