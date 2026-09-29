package com.dmg.notify.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dmg.notify.channel.Channel;
import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.tenant.Tenant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"app.dispatch.send-timeout-ms=300", "app.dispatch.pool-size=1"})
class SendTimeoutIntegrationTest extends AbstractIntegrationTest {

    /** First call hangs (interruptibly) far beyond the timeout; later calls succeed. */
    static class HangsOnce implements Channel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        @Override public ChannelType type() { return ChannelType.EMAIL; }
        @Override public void send(DeliveryRequest r) {
            if (calls.incrementAndGet() == 1) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    throw new TransientChannelException("interrupted");
                }
            }
            successes.incrementAndGet();
        }
    }

    @Test
    void hungProviderIsInterruptedRecordedAsTransientAndRetried() throws Exception {
        HangsOnce hangs = new HangsOnce();
        registry.register(hangs);
        Tenant t = newTenant(1000, 1000, 3);
        Notification n = submit(t, "a@example.com");

        long t0 = System.nanoTime();
        dispatcher.runOnce();
        // the single pool thread is freed by the timeout, long before the 30s sleep would have ended
        assertThat(executors.awaitIdle(5_000)).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(5_000L);

        Notification after = notificationRepo.findById(n.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(after.getLastError()).contains("timed out");

        drain(10); // retry goes through on the same (now free) thread
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT));
        assertThat(hangs.successes.get()).isEqualTo(1);
        assertThat(hangs.calls.get()).isEqualTo(2);
    }

    @Test
    void interruptFlagDoesNotLeakIntoTheNextTaskOnTheSameThread() throws Exception {
        HangsOnce hangs = new HangsOnce();
        registry.register(hangs);
        Tenant t = newTenant(1000, 1000, 3);
        Notification first = submit(t, "a@example.com");
        Notification second = submit(t, "b@example.com");

        dispatcher.runOnce();      // first hangs and is interrupted; second waits in the queue of the single thread
        executors.awaitIdle(5_000);
        drain(10);

        assertThat(statusOf(first.getId())).isEqualTo(NotificationStatus.SENT);
        assertThat(statusOf(second.getId())).isEqualTo(NotificationStatus.SENT);
    }
}
