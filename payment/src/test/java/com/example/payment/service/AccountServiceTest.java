package com.example.payment.service;

import com.example.payment.domain.Account;
import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.exception.InsufficientFundsException;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rolled back after each test, so the seeded account is untouched for other test classes. */
@SpringBootTest
@Transactional
class AccountServiceTest {

    @Autowired AccountService accountService;
    @Autowired AccountQueryService accounts;
    @Autowired PaymentRepository payments;
    @Autowired LedgerEntryRepository ledger;

    Account account;

    @BeforeEach
    void depositThousand() {
        account = accounts.current();
        Payment deposit = payments.save(Payment.newDeposit(account, 1000, "USD", "vendor-" + UUID.randomUUID()));
        accountService.deposit(deposit.getId());
    }

    @Test
    void depositCreditsBalance() {
        assertThat(account.getBalance()).isEqualTo(1000);
        assertThat(account.available()).isEqualTo(1000);
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    void holdThenSettleRemovesMoney() {
        Payment withdrawal = newWithdrawal(100);

        accountService.hold(withdrawal.getId());
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(account.getBalance()).isEqualTo(1000);
        assertThat(account.getHeld()).isEqualTo(100);
        assertThat(account.available()).isEqualTo(900);

        withdrawal.transitionTo(WithdrawalStatus.INITIATED);
        accountService.settle(withdrawal.getId());
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(account.getBalance()).isEqualTo(900);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(900);
        assertThat(ledger.count()).isEqualTo(3); // DEPOSIT, HOLD, SETTLE
    }

    @Test
    void holdThenReleaseRestoresAvailable() {
        Payment withdrawal = newWithdrawal(100);

        accountService.hold(withdrawal.getId());
        assertThat(account.available()).isEqualTo(900);

        accountService.release(withdrawal.getId());
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(account.getBalance()).isEqualTo(1000);
        assertThat(account.getHeld()).isZero();
        assertThat(account.available()).isEqualTo(1000);
        assertThat(ledger.count()).isEqualTo(3); // DEPOSIT, HOLD, RELEASE
    }

    @Test
    void holdFailsWhenFundsAreInsufficient() {
        Payment withdrawal = newWithdrawal(1001);

        assertThatThrownBy(() -> accountService.hold(withdrawal.getId()))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getHeld()).isZero();
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.CREATED);
    }

    private Payment newWithdrawal(long amount) {
        return payments.save(Payment.newWithdrawal(account, amount, "USD", "key-" + UUID.randomUUID()));
    }
}
