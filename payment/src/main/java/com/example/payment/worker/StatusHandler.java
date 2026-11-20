package com.example.payment.worker;

import com.example.payment.domain.Payment;
import com.example.payment.domain.WithdrawalStatus;

/** Advances a withdrawal by one step for one status. Exactly one handler per status. */
interface StatusHandler {

    WithdrawalStatus status();

    StepResult handle(Payment payment);
}
