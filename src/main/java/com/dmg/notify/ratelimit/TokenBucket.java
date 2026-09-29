package com.dmg.notify.ratelimit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lock-free token bucket implemented as GCRA (generic cell rate algorithm): a single atomic
 * "theoretical arrival time" is advanced by one emission interval per permit via CAS.
 * Allows bursts of up to {@code burst} permits, then a steady {@code ratePerSecond}.
 */
public final class TokenBucket {
    private final long intervalNanos;
    private final long burstToleranceNanos;
    private final int ratePerSecond;
    private final int burst;
    private final LongSupplier nanoClock;
    private final AtomicLong tat;

    public TokenBucket(int ratePerSecond, int burst, LongSupplier nanoClock) {
        if (ratePerSecond < 1 || burst < 1) throw new IllegalArgumentException("rate and burst must be >= 1");
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.intervalNanos = 1_000_000_000L / ratePerSecond;
        this.burstToleranceNanos = intervalNanos * burst;
        this.nanoClock = nanoClock;
        this.tat = new AtomicLong(Long.MIN_VALUE);
    }

    public boolean tryAcquire() {
        while (true) {
            long now = nanoClock.getAsLong();
            long current = tat.get();
            long base = current == Long.MIN_VALUE ? now : Math.max(current, now);
            long next = base + intervalNanos;
            if (next - now > burstToleranceNanos) return false;
            if (tat.compareAndSet(current, next)) return true;
        }
    }

    public boolean hasSameConfig(int ratePerSecond, int burst) {
        return this.ratePerSecond == ratePerSecond && this.burst == burst;
    }
}
