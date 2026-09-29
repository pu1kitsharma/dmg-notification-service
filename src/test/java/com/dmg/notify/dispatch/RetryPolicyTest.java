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
    void delayIsCappedAndDoesNotOverflow() {
        RetryPolicy p = new RetryPolicy(1000, 60_000, new Random(1));
        assertThat(p.delayAfter(100).toMillis()).isBetween(30_000L, 60_000L);
    }
}
