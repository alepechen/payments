package com.example.payment.worker;

import com.example.payment.domain.Account;
import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.AccountQueryService;
import com.example.payment.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Rolled back after each test, so the seeded account is untouched for other test classes. */
@SpringBootTest
@Transactional
class CreatedStatusHandlerTest {

    @Autowired CreatedStatusHandler handler;
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
    void enoughFundsHoldsAndMovesToHeld() {
        Payment withdrawal = newWithdrawal(100);

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(withdrawal.getAttempts()).isZero();
        assertThat(withdrawal.getLastError()).isNull();
        assertThat(account.getHeld()).isEqualTo(100);
        assertThat(ledger.count()).isEqualTo(2); // DEPOSIT, HOLD
    }

    @Test
    void insufficientFundsDeclinesWithoutHold() {
        Payment withdrawal = newWithdrawal(1001);

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(withdrawal.getLastError()).isEqualTo("insufficient funds");
        assertThat(account.getHeld()).isZero();
        assertThat(ledger.count()).isEqualTo(1); // DEPOSIT only
    }

    @Test
    void paymentNoLongerCreatedIsSkipped() {
        Payment withdrawal = newWithdrawal(100);
        withdrawal.decline("cancelled elsewhere");

        assertThat(handler.handle(withdrawal)).isEqualTo(StepResult.DONE);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.DECLINED);
        assertThat(withdrawal.getLastError()).isEqualTo("cancelled elsewhere");
        assertThat(account.getHeld()).isZero();
        assertThat(ledger.count()).isEqualTo(1);
    }

    private Payment newWithdrawal(long amount) {
        return payments.save(Payment.newWithdrawal(account, amount, "USD", "key-" + UUID.randomUUID()));
    }
}
