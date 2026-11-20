package com.example.payment.worker;

import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentType;
import com.example.payment.domain.WithdrawalStatus;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.WithdrawalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
class PayoutWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutWorker.class);

    private final PaymentRepository paymentRepository;
    private final WithdrawalService withdrawalService;
    private final Map<WithdrawalStatus, StatusHandler> handlers = new EnumMap<>(WithdrawalStatus.class);
    private Instant pausedUntil = Instant.EPOCH;   // only the scheduler thread touches this

    PayoutWorker(PaymentRepository paymentRepository, WithdrawalService withdrawalService,
                 List<StatusHandler> statusHandlers) {
        this.paymentRepository = paymentRepository;
        this.withdrawalService = withdrawalService;
        for (StatusHandler h : statusHandlers) {
            if (handlers.put(h.status(), h) != null) {
                throw new IllegalStateException("More than one handler for status " + h.status());
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        Instant now = Instant.now();
        if (now.isBefore(pausedUntil)) {
            return;                                                                  // vendor throttled us: wait it out
        }
        List<Payment> due = paymentRepository
                .findTop10ByTypeAndStatusInAndNextAttemptAtBeforeOrderByNextAttemptAt(
                        PaymentType.WITHDRAWAL, handlers.keySet(), now);   // snapshot, no transaction held
        for (Payment p : due) {
            try {
                if (step(p) instanceof StepResult.Throttled t) {
                    // every remaining payment would be refused too: leave them untouched for after the pause
                    pausedUntil = Instant.now().plus(t.retryAfter());
                    log.warn("vendor throttled, pausing until {}", pausedUntil);
                    break;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // lost a race with another writer between the reload and the commit: not an error
                log.info("payment={} changed concurrently, next tick will look again", p.getId());
            } catch (Exception e) {
                log.error("payment={} step failed", p.getId(), e);
                recordFailure(p, e);                                                 // back off, don't retry every tick
            }
        }
    }

    private void recordFailure(Payment p, Exception cause) {
        try {
            withdrawalService.recordFailure(p.getId(), String.valueOf(cause.getMessage()));
        } catch (Exception e) {
            log.error("payment={} could not record failure", p.getId(), e);          // still must not stop the loop
        }
    }

    private StepResult step(Payment p) {
        StatusHandler handler = handlers.get(p.getStatus());
        if (handler == null) {
            throw new IllegalStateException("No handler for status " + p.getStatus());
        }
        return handler.handle(p);
    }
}
