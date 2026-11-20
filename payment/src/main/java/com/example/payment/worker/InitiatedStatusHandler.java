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
class InitiatedStatusHandler implements StatusHandler {

    private final VendorGateway vendor;
    private final AccountService accountService;
    private final WithdrawalService withdrawalService;

    InitiatedStatusHandler(VendorGateway vendor, AccountService accountService, WithdrawalService withdrawalService) {
        this.vendor = vendor;
        this.accountService = accountService;
        this.withdrawalService = withdrawalService;
    }

    @Override
    public WithdrawalStatus status() {
        return WithdrawalStatus.INITIATED;
    }

    @Override
    public StepResult handle(Payment p) {
        // money may already be moving: never give up on Retryable here
        return switch (vendor.confirm(p.getVendorPaymentId())) {
            case VendorResult.Success<VendorPayment> s -> {
                accountService.settle(p.getId());                                         // tx: SETTLE + COMPLETED
                yield StepResult.DONE;
            }
            case VendorResult.Rejected<VendorPayment> r -> {
                accountService.release(p.getId());                                        // tx: RELEASE + DECLINED
                yield StepResult.DONE;
            }
            case VendorResult.Retryable<VendorPayment> r -> {
                withdrawalService.scheduleRetry(p.getId(), r.reason());                   // no attempt limit here
                yield StepResult.DONE;
            }
            case VendorResult.Throttled<VendorPayment> t -> {
                withdrawalService.deferRetry(p.getId(), t.retryAfter(), t.reason());
                yield new StepResult.Throttled(t.retryAfter());
            }
        };
    }
}
