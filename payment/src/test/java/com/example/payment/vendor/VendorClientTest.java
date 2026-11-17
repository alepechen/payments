package com.example.payment.vendor;

import com.example.payment.vendor.VendorResult.Rejected;
import com.example.payment.vendor.VendorResult.Retryable;
import com.example.payment.vendor.VendorResult.Success;
import com.example.payment.vendor.VendorResult.Throttled;
import com.example.vendor.api.VendorPaths;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Proves the retry rules against WireMock, without a Spring context.
 * Same package as the production code so it can reuse VendorResilienceConfig's factories.
 */
class VendorClientTest {

    @RegisterExtension
    static WireMockExtension vendor = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String CREATE = VendorPaths.PAYMENT;
    private final UUID ref = UUID.randomUUID();

    @Test
    void transientErrorsAreRetriedWithTheSameReference() {
        stubSequence(serverError(), serverError(), okJson(payment("CREATED")));

        var result = gateway().createPayout(ref, 1_000, "CAD");

        assertThat(result).isInstanceOf(Success.class);
        vendor.verify(3, postRequestedFor(urlPathEqualTo(CREATE)).withRequestBody(containing(ref.toString())));
    }

    @Test
    void rejectionIsNotRetried() {
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(status(422).withBody("{\"error\":\"invalid\"}")));

        var result = gateway().createPayout(ref, 1_000, "CAD");

        assertThat(result).isInstanceOf(Rejected.class);
        vendor.verify(1, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void vendorRejectedStatusIsRejected() {
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(okJson(payment("REJECTED"))));

        var result = gateway().createPayout(ref, 1_000, "CAD");

        assertThat(result).isInstanceOf(Rejected.class);
        vendor.verify(1, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void badCredentialsAreNotRetried() {
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(status(401)));
        VendorClient gateway = gateway();

        assertThatThrownBy(() -> gateway.createPayout(ref, 1_000, "CAD"))
                .isInstanceOf(VendorIntegrationException.class);
        vendor.verify(1, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void unexpected4xxIsABugNotARejection() {
        // A 409 on confirm must never release the hold, so it is not a Rejected.
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(status(409)));
        VendorClient gateway = gateway();

        assertThatThrownBy(() -> gateway.createPayout(ref, 1_000, "CAD"))
                .isInstanceOf(VendorIntegrationException.class);
        vendor.verify(1, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void getUnknownIdIsRejected404() {
        vendor.stubFor(get(urlPathEqualTo("/payment/x")).willReturn(status(404)));

        var result = gateway().get("x");

        assertThat(result).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.status()).isEqualTo(404));
        vendor.verify(1, getRequestedFor(urlPathEqualTo("/payment/x")));
    }

    @Test
    void rateLimitIsNotRetriedAndCarriesRetryAfterForTheWorker() {
        // Even a short Retry-After is not retried inline: one call, then back to the worker.
        stubSequence(status(429).withHeader("Retry-After", "1"), okJson(payment("CREATED")));

        var result = gateway().createPayout(ref, 1_000, "CAD");

        assertThat(result).isInstanceOfSatisfying(Throttled.class,
                t -> assertThat(t.retryAfter()).isEqualTo(Duration.ofSeconds(1)));
        vendor.verify(1, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void rateLimitDoesNotOpenTheBreaker() {
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(status(429).withHeader("Retry-After", "60")));
        VendorClient gateway = gateway();

        for (int i = 0; i < 6; i++) { // more than the breaker's window of 4
            assertThat(gateway.createPayout(ref, 1_000, "CAD")).isInstanceOf(Throttled.class);
        }

        assertThat(gateway.isCircuitOpen()).isFalse(); // over quota is not "vendor down"
        vendor.verify(6, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void lostResponseIsRetriedThenReportedAsRetryable() {
        // Delay longer than the 300 ms read timeout: the vendor "did it", we never heard back.
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(okJson(payment("CREATED")).withFixedDelay(1_000)));

        var result = gateway().createPayout(ref, 1_000, "CAD");

        assertThat(result).isInstanceOf(Retryable.class);
        vendor.verify(3, postRequestedFor(urlPathEqualTo(CREATE)).withRequestBody(containing(ref.toString())));
    }

    @Test
    void everyRetryTakesARateLimiterPermit() {
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(serverError()));
        // Only 2 permits per minute and no waiting: the third attempt must not reach the vendor.

        var result = gateway(2).createPayout(ref, 1_000, "CAD");

        assertThat(result).isInstanceOf(Throttled.class);
        vendor.verify(2, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void sustainedOutageOpensTheBreakerThenItRecovers() {
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(status(503)));
        VendorClient gateway = gateway();

        // Window of 4: 3 attempts on the first call + 1 on the second open the breaker.
        assertThat(gateway.createPayout(ref, 1_000, "CAD")).isInstanceOf(Retryable.class);
        assertThat(gateway.createPayout(ref, 1_000, "CAD")).isInstanceOf(Throttled.class);
        // After that nothing reaches the vendor and Retry stops at once.
        for (int i = 0; i < 3; i++) {
            assertThat(gateway.createPayout(ref, 1_000, "CAD")).isInstanceOf(Throttled.class);
        }
        assertThat(gateway.isCircuitOpen()).isTrue();
        vendor.verify(4, postRequestedFor(urlPathEqualTo(CREATE)));

        vendor.resetAll();
        vendor.stubFor(post(urlPathEqualTo(CREATE)).willReturn(okJson(payment("CREATED"))));
        await().atMost(Duration.ofSeconds(2)).until(() -> !gateway.isCircuitOpen()); // half-open

        assertThat(gateway.createPayout(ref, 1_000, "CAD")).isInstanceOf(Success.class);
        assertThat(gateway.isCircuitOpen()).isFalse();
    }

    // --- helpers -----------------------------------------------------------------------------

    private VendorClient gateway() {
        return gateway(1_000);
    }

    private VendorClient gateway(int ratePermits) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // same as VendorConfig
                .connectTimeout(Duration.ofMillis(500))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(300));
        RestClient restClient = RestClient.builder().baseUrl(vendor.baseUrl()).requestFactory(factory).build();

        return new VendorClient(
                restClient,
                Retry.of("vendor", VendorResilienceConfig.retryConfig(3, Duration.ofMillis(10))),
                RateLimiter.of("vendor", RateLimiterConfig.custom()
                        .limitForPeriod(ratePermits)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()),
                CircuitBreaker.of("vendor", VendorResilienceConfig.circuitBreakerConfig(4, 4, Duration.ofMillis(300))));
    }

    private String payment(String status) {
        return """
                {"id":"vp-1","reference":"%s","type":"PAYOUT","status":"%s","amount":1000,"currency":"CAD"}
                """.formatted(ref, status);
    }

    private static void stubSequence(ResponseDefinitionBuilder... responses) {
        String state = STARTED;
        for (int i = 0; i < responses.length; i++) {
            String next = "step-" + (i + 1);
            vendor.stubFor(post(urlPathEqualTo(CREATE))
                    .inScenario("sequence")
                    .whenScenarioStateIs(state)
                    .willReturn(responses[i])
                    .willSetStateTo(next));
            state = next;
        }
    }
}
