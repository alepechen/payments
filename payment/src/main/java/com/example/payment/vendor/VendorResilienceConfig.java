package com.example.payment.vendor;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The Resilience4j policies VendorClient composes. Built in code rather than through the
 * Resilience4j starter, so the decoration order lives in VendorClient and there is no aspect
 * order to get wrong. One shared instance of each: create, confirm and get draw from one
 * 10-per-minute budget. The static factories hold the rules; tests reuse them with small numbers.
 */
@Configuration(proxyBeanMethods = false)
class VendorResilienceConfig {

    private static final String NAME = "vendor";

    @Bean
    Retry vendorRetry() {
        return Retry.of(NAME, retryConfig(3, Duration.ofMillis(200)));
    }

    @Bean
    RateLimiter vendorRateLimiter() {
        return RateLimiter.of(NAME, RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)   // don't block the worker; a refusal means "try later"
                .build());
    }

    @Bean
    CircuitBreaker vendorCircuitBreaker() {
        return CircuitBreaker.of(NAME, circuitBreakerConfig(10, 5, Duration.ofSeconds(30)));
    }

    static RetryConfig retryConfig(int maxAttempts, Duration initialBackoff) {
        return RetryConfig.<VendorResult<?>>custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(initialBackoff, 2.0, 0.5))
                // Only Retryable results are retried. When attempts run out, the last one is returned.
                .retryOnResult(result -> result instanceof VendorResult.Retryable<?>)
                // By default every exception is retried, including our own refusals and
                // VendorIntegrationException. None of them should be.
                .retryOnException(e -> false)
                .build();
    }

    static CircuitBreakerConfig circuitBreakerConfig(int windowSize, int minCalls, Duration openWait) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minCalls)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openWait)
                .permittedNumberOfCallsInHalfOpenState(2)
                // The worker skips vendor work while OPEN, so nothing would call through to move
                // the breaker to HALF_OPEN.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only Retryable means "vendor down". Rejected and Throttled are a healthy vendor.
                .recordResult(result -> result instanceof VendorResult.Retryable<?>)
                // Exceptions mean "a person must fix this", not that the vendor is down.
                .recordException(e -> false)
                .build();
    }
}
