package com.dmg.notify.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.tenant.Tenant;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Real backoff values (the shared test context uses a few ms, which would hide this). */
@TestPropertySource(properties = {"app.retry.base-delay-ms=1000", "app.retry.max-delay-ms=600000"})
class ReplayBackoffIntegrationTest extends AbstractIntegrationTest {

    private void cycle() throws InterruptedException {
        dispatcher.runOnce();
        executors.awaitIdle(5_000);
    }

    @Test
    void firstRetryAfterAReplayWaitsTheBaseDelayNotTheDelayOfTheAbsoluteAttemptNumber() throws Exception {
        Tenant t = newTenant(1000, 1000, 5);
        Notification n = submit(t, "fail-always@example.com");
        for (int i = 0; i < 5; i++) { // 5 attempts, moving the clock past each backoff
            cycle();
            clock.advance(Duration.ofMinutes(15));
        }
        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.DEAD);

        notificationService.replay(t.getId(), n.getId());
        cycle(); // attempt 6 = first attempt of the fresh budget; it fails transiently again

        Notification after = notificationRepo.findById(n.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.PENDING);
        long delayMs = Duration.between(clock.instant(), after.getNextAttemptAt()).toMillis();
        // base 1000ms, equal jitter => [500, 1000]. Using the absolute attempt number (6) would give [16000, 32000].
        assertThat(delayMs).isBetween(300L, 1_100L);
    }
}
