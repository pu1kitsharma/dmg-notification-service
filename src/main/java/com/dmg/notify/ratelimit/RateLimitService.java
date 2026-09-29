package com.dmg.notify.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Per-tenant buckets plus one global bucket. Buckets are rebuilt when an admin changes the limits. */
@Service
public class RateLimitService {
    private final Map<Long, TokenBucket> tenantBuckets = new ConcurrentHashMap<>();
    private final AtomicReference<TokenBucket> globalBucket = new AtomicReference<>();
    private final LongSupplier nanoClock;

    @Autowired
    public RateLimitService() { this(System::nanoTime); }

    public RateLimitService(LongSupplier nanoClock) { this.nanoClock = nanoClock; }

    /** Tenant limit is checked first so a throttled tenant does not burn global capacity. */
    public boolean tryAcquire(long tenantId, int tenantRate, int tenantBurst, int globalRate, int globalBurst) {
        TokenBucket tb = tenantBuckets.compute(tenantId, (k, b) ->
                b != null && b.hasSameConfig(tenantRate, tenantBurst) ? b : new TokenBucket(tenantRate, tenantBurst, nanoClock));
        if (!tb.tryAcquire()) return false;
        TokenBucket gb = globalBucket.updateAndGet(b ->
                b != null && b.hasSameConfig(globalRate, globalBurst) ? b : new TokenBucket(globalRate, globalBurst, nanoClock));
        if (gb.tryAcquire()) return true;
        tb.release(); // global rejection must not cost the tenant a token
        return false;
    }

    /** Give back the permits of a successful {@link #tryAcquire} that was not used (lost claim, pool saturated). */
    public void refund(long tenantId) {
        TokenBucket tb = tenantBuckets.get(tenantId);
        if (tb != null) tb.release();
        TokenBucket gb = globalBucket.get();
        if (gb != null) gb.release();
    }
}
