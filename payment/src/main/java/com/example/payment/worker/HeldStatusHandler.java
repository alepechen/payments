package com.example.payment.worker;

import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.service.AccountService;
import com.example.payment.service.WithdrawalService;
import com.example.payment.vendor.VendorGateway;
import com.example.payment.vendor.VendorResult;
import com.example.vendor.api.VendorPayment;
import org.springframework.stereotype.Component;

@Component
class HeldStatusHandler implements StatusHandler {

    private static final int MAX_ATTEMPTS = 5;

    private final VendorGateway vendor;
    private final AccountService accountService;
    private final WithdrawalService withdrawalService;

    HeldStatusHandler(VendorGateway vendor, AccountService accountService, WithdrawalService withdrawalService) {
        this.vendor = vendor;
        this.accountService = accountService;
        this.withdrawalService = withdrawalService;
    }

    @Override
    public WithdrawalStatus status() {
        return WithdrawalStatus.HELD;
    }

    @Override
    public StepResult handle(Payment p) {
        // no tx around the vendor call
        return switch (vendor.createPayout(p.getId(), p.getAmount(), p.getCurrency())) {
            case VendorResult.Success<VendorPayment> s -> {
                withdrawalService.markInitiated(p.getId(), s.value().id());              // tx
                yield StepResult.DONE;
            }
            case VendorResult.Rejected<VendorPayment> r -> {
                accountService.release(p.getId());                                        // tx: RELEASE + DECLINED
                yield StepResult.DONE;
            }
            case VendorResult.Retryable<VendorPayment> r -> {
                if (p.getAttempts() + 1 >= MAX_ATTEMPTS) {
                    accountService.release(p.getId());                                    // out of attempts: cancel the hold
                } else {
                    withdrawalService.scheduleRetry(p.getId(), r.reason());               // tx: attempts++, back off
                }
                yield StepResult.DONE;
            }
            case VendorResult.Throttled<VendorPayment> t -> {
                withdrawalService.deferRetry(p.getId(), t.retryAfter(), t.reason());      // tx: no attempt counted
                yield new StepResult.Throttled(t.retryAfter());
            }
        };
    }
}
