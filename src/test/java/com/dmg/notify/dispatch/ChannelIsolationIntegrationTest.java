package com.dmg.notify.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dmg.notify.channel.Channel;
import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationDtos.SubmitRequest;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.template.TemplateService;
import com.dmg.notify.tenant.Tenant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Tiny pools (1 thread + 1 queue slot per channel) so saturation is reachable with a handful of rows. */
@TestPropertySource(properties = {"app.dispatch.pool-size=1", "app.dispatch.queue-capacity=1"})
class ChannelIsolationIntegrationTest extends AbstractIntegrationTest {

    /** A provider that hangs until released: fills the SMS pool. */
    static class BlockedChannel implements Channel {
        final CountDownLatch release = new CountDownLatch(1);
        @Override public ChannelType type() { return ChannelType.SMS; }
        @Override public void send(DeliveryRequest r) {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransientChannelException("interrupted");
            }
        }
    }

    @Test
    void saturatedSmsPoolDoesNotBlockTheSameTenantsEmail() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        templateService.create(t.getId(), new TemplateService.CreateTemplateRequest("welcome", ChannelType.SMS, null, "Hi {{name}}"));
        BlockedChannel sms = new BlockedChannel();
        registry.register(sms);
        for (int i = 0; i < 4; i++) { // capacity is 2 (1 running + 1 queued): the 3rd and 4th are rejected
            notificationService.submit(t.getId(), new SubmitRequest(ChannelType.SMS, "welcome", "+1555000" + (1000 + i),
                    Map.of("name", "x"), null), null);
        }
        Notification mail = submit(t, "a@example.com"); // newest row: sits behind the SMS rows

        try {
            dispatcher.runOnce(); // one cycle only

            await().atMost(Duration.ofSeconds(5)).untilAsserted(
                    () -> assertThat(statusOf(mail.getId())).isEqualTo(NotificationStatus.SENT));
            // the rejected SMS rows were released, not lost
            assertThat(notificationRepo.findAll().stream()
                    .filter(n -> n.getChannel() == ChannelType.SMS && n.getStatus() == NotificationStatus.PENDING)).hasSize(2);
        } finally {
            sms.release.countDown();
            // let the released SMS workers finish writing their outcomes; contexts share one database, so a straggler
            // would otherwise collide with the next test class's cleanup
            executors.awaitIdle(30_000);
        }
    }
}
