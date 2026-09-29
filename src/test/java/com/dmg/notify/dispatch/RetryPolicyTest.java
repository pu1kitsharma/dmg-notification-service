package com.dmg.notify.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void delayGrowsExponentiallyWithinJitterBounds() {
        RetryPolicy p = new RetryPolicy(1000, 60_000, new Random(7));
        for (int attempt = 1; attempt <= 5; attempt++) {
            long ceiling = 1000L << (attempt - 1);
            for (int i = 0; i < 200; i++) {
                assertThat(p.delayAfter(attempt).toMillis()).isBetween(ceiling / 2, ceiling);
            }
        }
    }

    @Test
    void jitterIsIndependentAcrossPoolThreads() throws Exception {
        // production wiring: one RetryPolicy bean built on the main thread, used from many worker threads
        RetryPolicy p = new RetryPolicy(new RetryProperties(2000, 300_000));
        int threads = 8;
        java.util.List<java.util.List<Long>> perThread = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Thread> ts = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> { // brand-new threads, exactly like freshly created pool workers
                java.util.List<Long> mine = new java.util.ArrayList<>();
                for (int k = 0; k < 5; k++) mine.add(p.delayAfter(4).toMillis());
                perThread.add(mine);
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join();

        // guard: identical sequences on every fresh thread would mean synchronized retries (stampede)
        assertThat(new java.util.HashSet<>(perThread)).hasSizeGreaterThan(1);
    }

    @Test
    void delayIsCappedAndDoesNotOverflow() {
        RetryPolicy p = new RetryPolicy(1000, 60_000, new Random(1));
        assertThat(p.delayAfter(100).toMillis()).isBetween(30_000L, 60_000L);
    }
}
