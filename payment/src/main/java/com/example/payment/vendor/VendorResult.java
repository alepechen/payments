package com.example.payment.vendor;

import java.time.Duration;
import java.util.function.Function;

/**
 * Every expected outcome of a vendor call. The worker switches over it, and the compiler
 * checks that every case is handled.
 *
 * Only things a person has to fix (bad credentials, an unexpected 4xx, a response we can't read,
 * bugs) are thrown, as VendorIntegrationException or whatever the bug throws.
 */
public sealed interface VendorResult<T> {

    /** 2xx with a body. */
    record Success<T>(T value) implements VendorResult<T> {
    }

    /**
     * A definitive business "no": 422, or a payment the vendor marked REJECTED. For get, also
     * 404 (unknown id). Retrying won't help.
     */
    record Rejected<T>(int status, String reason) implements VendorResult<T> {
    }

    /**
     * Something broke on the way (5xx, 408, connection problem, lost response). Retried inline by
     * the gateway and counted by the circuit breaker; if it is still returned, retries ran out.
     * The request may have reached the vendor, which is safe: every vendor call is idempotent.
     */
    record Retryable<T>(String reason) implements VendorResult<T> {
    }

    /**
     * We didn't get to try: vendor 429, our own rate limiter, or the breaker is open.
     * Never retried inline. retryAfter is never null.
     */
    record Throttled<T>(Duration retryAfter, String reason) implements VendorResult<T> {
    }

    /** Changes a Success's value and passes every other outcome through unchanged. */
    default <R> VendorResult<R> map(Function<? super T, ? extends R> mapper) {
        return switch (this) {
            case Success<T> s -> new Success<>(mapper.apply(s.value()));
            case Rejected<T> r -> new Rejected<>(r.status(), r.reason());
            case Retryable<T> r -> new Retryable<>(r.reason());
            case Throttled<T> t -> new Throttled<>(t.retryAfter(), t.reason());
        };
    }
}
