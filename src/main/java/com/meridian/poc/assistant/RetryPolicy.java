package com.meridian.poc.assistant;

import com.meridian.poc.common.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded retries with exponential back-off and <b>full jitter</b> (sleep = random(0, min(cap, base·2^n))),
 * constrained by a deadline so the customer-facing budget is never exceeded.
 * Callers must only use this for idempotent or idempotency-keyed operations.
 */
public final class RetryPolicy {
    @FunctionalInterface
    public interface Call<T> { T run() throws RemoteError; }

    private final int maxAttempts;
    private final Duration base, cap;

    public RetryPolicy(int maxAttempts, Duration base, Duration cap) {
        this.maxAttempts = maxAttempts; this.base = base; this.cap = cap;
    }

    public <T> T execute(String label, Call<T> call, Instant deadline) throws RemoteError {
        RemoteError last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.run();
            } catch (RemoteError e) {
                last = e;
                if (!e.retryable || attempt == maxAttempts) break;
                Duration sleep = backoff(attempt, base, cap);
                if (Instant.now().plus(sleep).isAfter(deadline)) {
                    Log.warn("SAGA", "%s: attempt %d failed (%s); no budget left for another try", label, attempt, e.getMessage());
                    break;
                }
                Log.warn("SAGA", "%s: attempt %d failed (%s) -> retry in %d ms", label, attempt, e.getMessage(), sleep.toMillis());
                try { Thread.sleep(sleep.toMillis()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }

    public static Duration backoff(int attempt, Duration base, Duration cap) {
        long ceiling = Math.min(cap.toMillis(), base.toMillis() * (1L << Math.min(attempt, 16)));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling / 4, ceiling + 1)); // full jitter, small floor for readability
    }
}
