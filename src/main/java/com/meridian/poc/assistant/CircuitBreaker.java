package com.meridian.poc.assistant;

import com.meridian.poc.common.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Count-based circuit breaker per downstream (design §9): opens when the failure rate over the last
 * N calls crosses the threshold, fails fast while open, then lets a single probe through (half-open).
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name, tag;
    private final int window, minCalls;
    private final double failureRate;
    private final Duration openFor;
    private final Deque<Boolean> outcomes = new ArrayDeque<>();
    private State state = State.CLOSED;
    private Instant openedAt;

    public CircuitBreaker(String name, int window, int minCalls, double failureRate, Duration openFor) {
        this("SAGA", name, window, minCalls, failureRate, openFor);
    }

    public CircuitBreaker(String tag, String name, int window, int minCalls, double failureRate, Duration openFor) {
        this.tag = tag;
        this.name = name; this.window = window; this.minCalls = minCalls; this.failureRate = failureRate; this.openFor = openFor;
    }

    public <T> T call(RetryPolicy.Call<T> call) throws RemoteError {
        synchronized (this) {
            if (state == State.OPEN) {
                if (Instant.now().isBefore(openedAt.plus(openFor)))
                    throw new RemoteError(name + " circuit OPEN - failing fast", 503, true, null);
                state = State.HALF_OPEN;
                Log.info(tag, "%s circuit HALF_OPEN - sending a probe", name);
            }
        }
        try {
            T t = call.run();
            record(true);
            return t;
        } catch (RemoteError e) {
            // Only infrastructure failures count; a business 4xx says nothing about the downstream's health.
            record(!e.retryable);
            throw e;
        }
    }

    private synchronized void record(boolean success) {
        if (state == State.HALF_OPEN) {
            if (success) { state = State.CLOSED; outcomes.clear(); Log.info(tag, "%s circuit CLOSED", name); }
            else trip();
            return;
        }
        outcomes.addLast(success);
        while (outcomes.size() > window) outcomes.removeFirst();
        long failures = outcomes.stream().filter(s -> !s).count();
        if (outcomes.size() >= minCalls && (double) failures / outcomes.size() >= failureRate) trip();
    }

    private void trip() {
        state = State.OPEN;
        openedAt = Instant.now();
        outcomes.clear();
        Log.warn(tag, "%s circuit OPEN for %d s", name, openFor.toSeconds());
    }

    public synchronized State state() {
        if (state == State.OPEN && Instant.now().isAfter(openedAt.plus(openFor))) return State.HALF_OPEN;
        return state;
    }
}
