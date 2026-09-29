package com.dmg.notify.dispatch;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Exponential backoff with "equal jitter": half deterministic, half random, to avoid retry stampedes. */
@Component
public class RetryPolicy {
    /**
     * ThreadLocalRandom must be obtained on the thread that uses it ("ThreadLocalRandom.current().nextX(...)"), never
     * captured once and shared, so this delegates on every call. (On JDK 17/23 a captured instance happened to behave,
     * but that is not part of the contract.)
     */
    private static final RandomGenerator PER_THREAD = new RandomGenerator() {
        @Override public long nextLong() { return ThreadLocalRandom.current().nextLong(); }
        @Override public long nextLong(long bound) { return ThreadLocalRandom.current().nextLong(bound); }
    };

    private final long baseMs;
    private final long maxMs;
    private final RandomGenerator random;

    @Autowired
    public RetryPolicy(RetryProperties props) {
        this(props.baseDelayMs(), props.maxDelayMs(), PER_THREAD);
    }

    public RetryPolicy(long baseMs, long maxMs, RandomGenerator random) {
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.random = random;
    }

    /** @param failedAttempt 1-based number of the attempt that just failed */
    public Duration delayAfter(int failedAttempt) {
        int shift = Math.min(Math.max(failedAttempt - 1, 0), 30);
        long ceiling = Math.min(maxMs, baseMs * (1L << shift));
        long half = ceiling / 2;
        long jitter = half == 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(half + jitter);
    }
}
