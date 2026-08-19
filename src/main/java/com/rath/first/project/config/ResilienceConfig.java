package com.rath.first.project.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resilience for the slow, remote, sometimes-flaky LLM call — layered ON TOP of Spring AI's
 * built-in retry (configured in application.properties). Three different jobs:
 *
 *   - Retry (Spring AI)  -> recover from a transient blip by trying again.
 *   - TimeLimiter        -> give up on ONE call that runs too long, instead of hanging the request.
 *   - CircuitBreaker     -> after MANY failures, "open" and fail fast for a while, so we don't
 *                           pile up 30-second timeouts and exhaust the thread pool during an outage.
 */
@Configuration
public class ResilienceConfig {

    /** Runs the blocking AI call on a background thread so the TimeLimiter can actually time it out. */
    @Bean
    public ExecutorService aiWorkerExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public TimeLimiter aiTimeLimiter() {
        return TimeLimiter.of("ai", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(30))   // LLMs are slow; 30s is generous but bounded
                .cancelRunningFuture(true)                 // cancel the future when we give up
                .build());
    }

    @Bean
    public CircuitBreaker aiCircuitBreaker() {
        return CircuitBreaker.of("ai", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                        // open if >50% of calls fail...
                .slidingWindowSize(10)                           // ...measured over the last 10 calls
                .waitDurationInOpenState(Duration.ofSeconds(20)) // stay open 20s, then allow a trial call
                .build());
    }
}
