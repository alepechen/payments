package com.example.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} payout worker. Off in tests ({@code payment.worker.enabled: false}),
 * where ticks are driven by hand.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payment.worker.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
