package com.example.payment.worker;

import java.time.Duration;

/** What a handler tells the worker after one step. */
sealed interface StepResult {

    /** The step ran (successfully or not); carry on with the batch. */
    record Done() implements StepResult {
    }

    /** The vendor refused to take calls; the rest of the batch would be refused too. */
    record Throttled(Duration retryAfter) implements StepResult {
    }

    StepResult DONE = new Done();
}
