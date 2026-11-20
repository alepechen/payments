package com.example.payment.worker;

import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.exception.InsufficientFundsException;
import com.example.payment.service.AccountService;
import com.example.payment.service.WithdrawalService;
import org.springframework.stereotype.Component;

@Component
class CreatedStatusHandler implements StatusHandler {

    private final AccountService accountService;
    private final WithdrawalService withdrawalService;

    CreatedStatusHandler(AccountService accountService, WithdrawalService withdrawalService) {
        this.accountService = accountService;
        this.withdrawalService = withdrawalService;
    }

    @Override
    public WithdrawalStatus status() {
        return WithdrawalStatus.CREATED;
    }

    @Override
    public StepResult handle(Payment p) {
        try {
            accountService.hold(p.getId());                                // tx: still CREATED? HOLD row, -> HELD, attempts reset
        } catch (InsufficientFundsException e) {
            withdrawalService.decline(p.getId(), "insufficient funds");    // tx: DECLINED, no money moved
        }
        return StepResult.DONE;
    }
}
