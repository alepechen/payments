package com.example.payment.vendor;

import com.example.payment.vendor.VendorResult.Rejected;
import com.example.payment.vendor.VendorResult.Retryable;
import com.example.payment.vendor.VendorResult.Success;
import com.example.payment.vendor.VendorResult.Throttled;
import com.example.vendor.api.CreatePayoutRequest;
import com.example.vendor.api.VendorPaths;
import com.example.vendor.api.VendorPayment;
import com.example.vendor.api.VendorPaymentStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class VendorClient implements VendorGateway {

    private static final Duration BREAKER_OPEN_WAIT = Duration.ofSeconds(30);
    private static final Duration RATE_LIMITED_WAIT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    /** Statuses that are a business "no". Any other 4xx is a bug on one side. */
    private static final Set<Integer> PAYOUT_REJECT_STATUSES = Set.of(422);
    private static final Set<Integer> GET_REJECT_STATUSES = Set.of(404, 422);

    private final RestClient restClient;
    private final Retry retry;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    VendorClient(RestClient vendorRestClient, Retry vendorRetry, RateLimiter vendorRateLimiter,
                 CircuitBreaker vendorCircuitBreaker) {
        this.restClient = vendorRestClient;
        this.retry = vendorRetry;
        this.rateLimiter = vendorRateLimiter;
        this.circuitBreaker = vendorCircuitBreaker;
    }

    @Override
    public VendorResult<VendorPayment> createPayout(UUID ref, long amount, String currency) {
        return withResilience(() -> rejectIfVendorRejected(requireMatch(send(restClient.post()
                .uri(VendorPaths.PAYMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreatePayoutRequest(ref, amount, currency)), PAYOUT_REJECT_STATUSES),
                ref, amount, currency)));
    }

    @Override
    public VendorResult<VendorPayment> confirm(String vendorPaymentId) {
        return withResilience(() -> rejectIfVendorRejected(send(restClient.post()
                .uri(VendorPaths.PAYMENT_CONFIRM, vendorPaymentId), PAYOUT_REJECT_STATUSES)));
    }

    @Override
    public VendorResult<VendorPayment> get(String vendorPaymentId) {
        return withResilience(() -> send(restClient.get()
                .uri(VendorPaths.PAYMENT_BY_ID, vendorPaymentId), GET_REJECT_STATUSES));
    }

    public boolean isCircuitOpen() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    private <T> VendorResult<T> withResilience(Supplier<VendorResult<T>> httpCall) {
        // Innermost to outermost. Retry is outside, so every attempt takes a rate-limiter permit
        // ("retries count too") and is seen by the breaker. The rate limiter is outside the
        // breaker, so its refusals never count as calls.
        Supplier<VendorResult<T>> call = CircuitBreaker.decorateSupplier(circuitBreaker, httpCall);
        call = RateLimiter.decorateSupplier(rateLimiter, call);
        call = Retry.decorateSupplier(retry, call);
        try {
            return call.get();
        } catch (RequestNotPermitted e) {
            return new Throttled<>(RATE_LIMITED_WAIT, "local rate limiter refused the call");
        } catch (CallNotPermittedException e) {
            return new Throttled<>(BREAKER_OPEN_WAIT, "circuit breaker is open");
        }
    }

    /** The vendor can say no with 200 and status REJECTED; give the worker one Rejected path. */
    private static VendorResult<VendorPayment> rejectIfVendorRejected(VendorResult<VendorPayment> result) {
        if (result instanceof Success<VendorPayment>(VendorPayment p) && p.status() == VendorPaymentStatus.REJECTED) {
            return new Rejected<>(200, "vendor payment " + p.id() + " is REJECTED");
        }
        return result;
    }

    private VendorResult<VendorPayment> send(RestClient.RequestHeadersSpec<?> request, Set<Integer> rejectStatuses) {
        try {
            // No onStatus handlers: Spring throws RestClientResponseException for any 4xx/5xx,
            // with status, headers and body already read into it.
            VendorPayment payment = request.retrieve().body(VendorPayment.class);
            if (payment == null) {
                return new Retryable<>("2xx with an empty body");
            }
            return new Success<>(payment);
        } catch (RestClientResponseException e) {
            return classify(e, rejectStatuses);
        } catch (ResourceAccessException e) {
            // Connection refused, read timeout, etc. Safe to retry: every vendor call is idempotent.
            return new Retryable<>("I/O error: " + e.getMessage());
        } catch (RestClientException e) {
            // Usually a body we can't parse: the vendor's API changed. A person has to look.
            throw new VendorIntegrationException("Unexpected response from vendor", e);
        }
    }

    private static <T> VendorResult<T> classify(RestClientResponseException e, Set<Integer> rejectStatuses) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        if (status == 429) {
            return new Throttled<>(retryAfter(e.getResponseHeaders()), "vendor rate limit: " + body);
        }
        if (status == 408 || e.getStatusCode().is5xxServerError()) {
            return new Retryable<>(status + ": " + body);
        }
        if (rejectStatuses.contains(status)) {
            return new Rejected<>(status, body);
        }
        // 400, 401, 403, 409, a 404 on create/confirm ...: a bug or a config problem on one side.
        // Never treat it as a rejection: after INITIATED that would release a hold on money that
        // may already be moving. The worker stores it in lastError and logs it.
        throw new VendorIntegrationException("Unexpected vendor response " + status + ": " + body, e);
    }

    /** Retry-After is either seconds ("120") or an HTTP date. */
    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return DEFAULT_RETRY_AFTER;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration wait = Duration.between(Instant.now(),
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException unparseable) {
                return DEFAULT_RETRY_AFTER;
            }
        }
    }

    /** The vendor must echo back what we sent. A mismatch is a bug, never a business "no". */
    private static VendorResult<VendorPayment> requireMatch(
            VendorResult<VendorPayment> result, UUID reference, long amount, String currency) {
        if (result instanceof Success<VendorPayment>(VendorPayment p)
                && (!reference.equals(p.reference()) || p.amount() != amount || !currency.equals(p.currency()))) {
            throw new VendorIntegrationException("Vendor payment " + p.id() + " does not match what we sent: expected "
                    + reference + "/" + amount + "/" + currency + ", got "
                    + p.reference() + "/" + p.amount() + "/" + p.currency());
        }
        return result;
    }
}
