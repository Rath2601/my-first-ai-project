package com.rath.first.project.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Wraps any AI call with a timeout + circuit breaker.
 *
 * Layers, outer to inner:  circuit breaker  ->  time limiter  ->  the call itself.
 *   - If the provider is down, the breaker is OPEN and we fail fast (no waiting at all).
 *   - If a single call hangs, the time limiter cuts it off after 30s.
 *   - Spring AI's own retry still handles transient blips underneath the call.
 *
 * Any failure (timeout / open circuit / provider error) is surfaced as HTTP 503 so the
 * caller can degrade gracefully instead of seeing a stack trace.
 */
@Service
public class ResilientChatService {

    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService worker;

    public ResilientChatService(CircuitBreaker aiCircuitBreaker,
                                TimeLimiter aiTimeLimiter,
                                ExecutorService aiWorkerExecutor) {
        this.circuitBreaker = aiCircuitBreaker;
        this.timeLimiter = aiTimeLimiter;
        this.worker = aiWorkerExecutor;
    }

    public <T> T execute(Supplier<T> aiCall) {
        // Run the blocking call on a worker thread so it CAN be timed out...
        Supplier<CompletableFuture<T>> futureSupplier =
                () -> CompletableFuture.supplyAsync(aiCall, worker);
        // ...then wrap it with the time limiter, then the circuit breaker.
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        Callable<T> guarded = CircuitBreaker.decorateCallable(circuitBreaker, timed);
        try {
            return guarded.call();
        } catch (Exception e) {
            // Timeout, open circuit, or provider failure -> tell the client we're temporarily degraded.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The assistant is temporarily unavailable. Please try again shortly.", e);
        }
    }
}
