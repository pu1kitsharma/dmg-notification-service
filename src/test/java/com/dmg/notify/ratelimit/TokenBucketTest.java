package com.dmg.notify.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void allowsBurstThenRejects() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        TokenBucket b = new TokenBucket(10, 5, clock::get);
        for (int i = 0; i < 5; i++) assertThat(b.tryAcquire()).as("permit %d", i).isTrue();
        assertThat(b.tryAcquire()).isFalse();
    }

    @Test
    void refillsAtSteadyRate() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket b = new TokenBucket(10, 1, clock::get); // 1 permit / 100ms
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();
        clock.addAndGet(99_000_000L);
        assertThat(b.tryAcquire()).isFalse();
        clock.addAndGet(2_000_000L);
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void neverExceedsBurstUnderContention() throws Exception {
        int burst = 50;
        AtomicLong frozen = new AtomicLong(42);
        TokenBucket b = new TokenBucket(1000, burst, frozen::get);
        int threads = 16;
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) if (b.tryAcquire()) granted.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(granted.get()).isEqualTo(burst);
    }
}
