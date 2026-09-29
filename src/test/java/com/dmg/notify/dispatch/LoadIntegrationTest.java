package com.dmg.notify.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.tenant.Tenant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Volume tests: the "at scale" claims backed by numbers. Sizes are chosen to finish in seconds on H2. */
class LoadIntegrationTest extends AbstractIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(LoadIntegrationTest.class);

    private void submitMany(Tenant t, String prefix, int count) {
        for (int i = 0; i < count; i++) submit(t, prefix + i + "@example.com");
    }

    private long count(Tenant t, NotificationStatus s) {
        return jdbc.queryForObject("select count(*) from notifications where tenant_id = ? and status = ?", Long.class, t.getId(), s.name());
    }

    @Test
    void tenThousandNotificationsAcrossTenantsAreDeliveredExactlyOnce() throws Exception {
        Tenant a = newTenant(1_000_000, 1_000_000, 3);
        Tenant b = newTenant(1_000_000, 1_000_000, 3);
        Tenant c = newTenant(1_000_000, 1_000_000, 3);
        submitMany(a, "a", 4000);
        submitMany(b, "b", 4000);
        submitMany(c, "c", 2000);

        int dispatchers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
        CountDownLatch start = new CountDownLatch(1);
        long t0 = System.nanoTime();
        for (int d = 0; d < dispatchers; d++) {
            pool.submit(() -> {
                start.await();
                // each cycle claims <= batch-per-tenant rows per tenant; loop until nothing is left
                while (jdbc.queryForObject("select count(*) from notifications where status in ('PENDING','PROCESSING')", Long.class) > 0) {
                    dispatcher.runOnce();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        executors.awaitIdle(30_000);
        double seconds = (System.nanoTime() - t0) / 1e9;

        log.info("LOAD: 10000 notifications delivered in {}s ({} msg/s) by {} dispatchers", String.format("%.1f", seconds),
                String.format("%.0f", 10_000 / seconds), dispatchers);
        assertThat(List.of(a, b, c)).allMatch(t -> count(t, NotificationStatus.SENT) == (t == c ? 2000 : 4000));
        assertThat(email.calls).hasSize(10_000);
        assertThat(email.calls.values()).allMatch(n -> n.get() == 1);
        assertThat(email.totalSuccesses()).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("select count(*) from notification_attempts", Long.class)).isEqualTo(10_000L);
    }

    @Test
    void lightTenantFinishesInBoundedCyclesBehindAHeavyBacklog() throws Exception {
        Tenant heavy = newTenant(1_000_000, 1_000_000, 3);
        Tenant light = newTenant(1_000_000, 1_000_000, 3);
        submitMany(heavy, "h", 3000);
        submitMany(light, "l", 100);

        // batch-per-tenant = 10 => the light tenant needs ceil(100/10) = 10 cycles regardless of the heavy backlog
        for (int cycle = 0; cycle < 10; cycle++) {
            dispatcher.runOnce();
            executors.awaitIdle(5000);
        }

        assertThat(count(light, NotificationStatus.SENT)).isEqualTo(100);
        assertThat(count(heavy, NotificationStatus.SENT)).isEqualTo(100); // heavy got the same share, no more
        assertThat(count(heavy, NotificationStatus.PENDING)).isEqualTo(2900);
    }

    @Test
    void perTenantRateLimitHoldsUnderLoadWhileOtherTenantsAreUnaffected() throws Exception {
        Tenant limited = newTenant(1, 50, 3); // burst 50, then 1/s
        Tenant free = newTenant(1_000_000, 1_000_000, 3);
        submitMany(limited, "x", 500);
        submitMany(free, "f", 500);

        long t0 = System.nanoTime();
        for (int i = 0; i < 60; i++) { // 60 cycles x 10 per tenant >= 500 for the free tenant
            dispatcher.runOnce();
            executors.awaitIdle(5000);
        }

        long elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000L;

        assertThat(count(free, NotificationStatus.SENT)).isEqualTo(500);
        // burst (50) + 1 token/s for however long the loop took (+2 slack); never anywhere near 500
        assertThat(count(limited, NotificationStatus.SENT)).isBetween(50L, 52L + elapsedSeconds);
        assertThat(count(limited, NotificationStatus.PENDING)).isGreaterThanOrEqualTo(500L - 52L - elapsedSeconds);
    }
}
