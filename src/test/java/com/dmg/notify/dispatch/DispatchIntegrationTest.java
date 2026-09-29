package com.dmg.notify.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmg.notify.notification.DeliveryAttemptRepository;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationEvent;
import com.dmg.notify.notification.NotificationEventRepository;
import com.dmg.notify.notification.NotificationStateMachine;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.tenant.Tenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DispatchIntegrationTest extends AbstractIntegrationTest {
    @Autowired NotificationEventRepository events;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired NotificationStateMachine stateMachine;
    @Autowired DeliveryOutcomeService outcomesRef;

    @Test
    void deliversAndWritesFullAuditTrail() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        Notification n = submit(t, "a@example.com");

        drain(3);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT);
        List<NotificationEvent> trail = events.findByNotificationIdOrderByIdAsc(n.getId());
        assertThat(trail).extracting(NotificationEvent::getToStatus)
                .containsExactly(NotificationStatus.PENDING, NotificationStatus.PROCESSING, NotificationStatus.SENT);
        assertThat(attempts.findByNotificationIdOrderByIdAsc(n.getId())).hasSize(1);
    }

    @Test
    void transientFailuresAreRetriedWithoutDuplicateDelivery() throws Exception {
        Tenant t = newTenant(1000, 1000, 5);
        email.transientFailuresPerKey = 2;
        Notification n = submit(t, "a@example.com");

        drain(15);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT);
        assertThat(email.calls.get(n.getId()).get()).isEqualTo(3);
        assertThat(email.successes.get(n.getId()).get()).isEqualTo(1);
        assertThat(attempts.findByNotificationIdOrderByIdAsc(n.getId())).hasSize(3);
    }

    @Test
    void movesToDeadAfterMaxAttempts() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        Notification n = submit(t, "fail-always@example.com");

        drain(20);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.DEAD);
        assertThat(email.calls.get(n.getId()).get()).isEqualTo(3);
    }

    @Test
    void permanentFailureIsNotRetried() throws Exception {
        Tenant t = newTenant(1000, 1000, 5);
        Notification n = submit(t, "fail-permanent@example.com");

        drain(10);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.DEAD);
        assertThat(email.calls.get(n.getId()).get()).isEqualTo(1);
    }

    @Test
    void scheduledNotificationWaitsUntilDue() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        Notification later = submitAt(t, "a@example.com", Instant.now().plusSeconds(3600));
        Notification soon = submitAt(t, "b@example.com", Instant.now().plusMillis(150));

        drain(2);
        assertThat(statusOf(later.getId())).isEqualTo(NotificationStatus.PENDING);
        assertThat(statusOf(soon.getId())).isEqualTo(NotificationStatus.PENDING);

        Thread.sleep(200);
        drain(3);
        assertThat(statusOf(soon.getId())).isEqualTo(NotificationStatus.SENT);
        assertThat(statusOf(later.getId())).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void rateLimitThrottlesButNeverDropsWork() throws Exception {
        Tenant t = newTenant(1, 3, 3); // burst of 3, then 1/sec
        List<Notification> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) all.add(submit(t, "u" + i + "@example.com"));

        int firstCycle = dispatcher.runOnce();
        executors.awaitIdle(2000);

        assertThat(firstCycle).isEqualTo(3);
        long sent = all.stream().filter(n -> statusOf(n.getId()) == NotificationStatus.SENT).count();
        long pending = all.stream().filter(n -> statusOf(n.getId()) == NotificationStatus.PENDING).count();
        assertThat(sent).isEqualTo(3);
        assertThat(pending).isEqualTo(5); // deferred, not lost
    }

    @Test
    void noisyTenantCannotStarveAnother() throws Exception {
        Tenant noisy = newTenant(10_000, 10_000, 3);
        Tenant quiet = newTenant(10_000, 10_000, 3);
        for (int i = 0; i < 100; i++) submit(noisy, "n" + i + "@example.com");
        List<Notification> quietOnes = new ArrayList<>();
        for (int i = 0; i < 5; i++) quietOnes.add(submit(quiet, "q" + i + "@example.com"));

        int dispatched = dispatcher.runOnce(); // one cycle only
        executors.awaitIdle(2000);

        assertThat(dispatched).isEqualTo(10 + 5); // batch-per-tenant cap for noisy, all of quiet
        assertThat(quietOnes).allMatch(n -> statusOf(n.getId()) == NotificationStatus.SENT);
    }

    @Test
    void concurrentDispatchersDeliverEachNotificationExactlyOnce() throws Exception {
        Tenant t = newTenant(100_000, 100_000, 3);
        List<Notification> all = new ArrayList<>();
        for (int i = 0; i < 300; i++) all.add(submit(t, "u" + i + "@example.com"));

        int dispatchers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
        CountDownLatch start = new CountDownLatch(1);
        for (int d = 0; d < dispatchers; d++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 40; i++) dispatcher.runOnce();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        executors.awaitIdle(10_000);

        assertThat(all).allMatch(n -> statusOf(n.getId()) == NotificationStatus.SENT);
        assertThat(email.calls).hasSize(300);
        assertThat(email.calls.values()).allMatch(c -> c.get() == 1);
        assertThat(email.totalSuccesses()).isEqualTo(300);
    }

    @Test
    void expiredLeaseIsRecoveredAndRetried() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        Notification n = submit(t, "a@example.com");
        // simulate a worker that claimed the row and then died: lease already in the past, no outcome recorded
        Instant now = Instant.now();
        assertThat(stateMachine.claim(n.getId(), now, now.minusSeconds(1))).contains(1);
        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.PROCESSING);

        drain(10);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT);
        assertThat(notificationRepo.findById(n.getId()).orElseThrow().getAttemptCount()).isEqualTo(2);
        assertThat(events.findByNotificationIdOrderByIdAsc(n.getId()))
                .extracting(NotificationEvent::getReason)
                .anyMatch(r -> r.contains("lease expired"));
    }

    @Test
    void staleWorkerCannotOverwriteNewerAttempt() throws Exception {
        Tenant t = newTenant(1000, 1000, 5);
        Notification n = submit(t, "a@example.com");
        Instant now = Instant.now();
        stateMachine.claim(n.getId(), now, now.minusSeconds(1)); // attempt 1, lease expired
        dispatcher.runOnce();                                    // reclaim -> PENDING (attempt 1 failed)
        Thread.sleep(10);
        dispatcher.runOnce();                                    // claim attempt 2
        executors.awaitIdle(2000);
        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT);

        // the original (slow) worker for attempt 1 finally reports in
        outcomesRef.recordFailure(n.getId(), 1, "late", false, now);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void deadNotificationCanBeReplayedAndGetsAFreshRetryBudget() throws Exception {
        Tenant t = newTenant(1000, 1000, 2);
        email.transientFailuresPerKey = 2; // outage: first two provider calls fail
        Notification n = submit(t, "a@example.com");
        drain(15);
        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.DEAD);

        notificationService.replay(t.getId(), n.getId()); // outage over
        drain(10);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.SENT);
        assertThat(email.successes.get(n.getId()).get()).isEqualTo(1);
        assertThat(attempts.findByNotificationIdOrderByIdAsc(n.getId())).extracting(a -> a.getAttemptNo())
                .containsExactly(1, 2, 3);
        assertThat(events.findByNotificationIdOrderByIdAsc(n.getId()))
                .extracting(NotificationEvent::getReason).anyMatch(r -> r.contains("replayed"));
    }

    @Test
    void replayedNotificationGetsFullBudgetBeforeDyingAgain() throws Exception {
        Tenant t = newTenant(1000, 1000, 2);
        Notification n = submit(t, "fail-always@example.com");
        drain(15);
        notificationService.replay(t.getId(), n.getId());
        drain(15);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.DEAD);
        assertThat(email.calls.get(n.getId()).get()).isEqualTo(4); // 2 + 2, not 3
    }

    @Test
    void cancelledNotificationIsNeverSent() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        Notification n = submitAt(t, "a@example.com", Instant.now().plusSeconds(60));
        notificationService.cancel(t.getId(), n.getId());

        drain(3);

        assertThat(statusOf(n.getId())).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(email.calls).isEmpty();
    }
}
