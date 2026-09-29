package com.dmg.notify.dispatch;

import com.dmg.notify.notification.DeliveryAttempt;
import com.dmg.notify.notification.DeliveryAttempt.Outcome;
import com.dmg.notify.notification.DeliveryAttemptRepository;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationStateMachine;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the result of an attempt. Every call is fenced by (status == PROCESSING, attemptCount == attemptNo)
 * so a slow worker whose lease was already reclaimed cannot overwrite a newer attempt.
 */
@Service
public class DeliveryOutcomeService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeService.class);

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final NotificationStateMachine state;
    private final TenantRepository tenants;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public DeliveryOutcomeService(NotificationRepository notifications, DeliveryAttemptRepository attempts,
                                  NotificationStateMachine state, TenantRepository tenants,
                                  RetryPolicy retryPolicy, Clock clock) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.state = state;
        this.tenants = tenants;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public void recordSuccess(String id, int attemptNo, Instant startedAt) {
        Notification n = fenced(id, attemptNo);
        if (n == null) return;
        attempts.save(new DeliveryAttempt(id, attemptNo, Outcome.SUCCESS, null, startedAt, clock.instant()));
        n.setLastError(null);
        state.transition(n, NotificationStatus.SENT, "delivered");
    }

    @Transactional
    public void recordFailure(String id, int attemptNo, String error, boolean permanent, Instant startedAt) {
        Notification n = fenced(id, attemptNo);
        if (n == null) return;
        Instant now = clock.instant();
        attempts.save(new DeliveryAttempt(id, attemptNo,
                permanent ? Outcome.PERMANENT_FAILURE : Outcome.TRANSIENT_FAILURE, error, startedAt, now));
        n.setLastError(error);
        int maxAttempts = tenants.findById(n.getTenantId()).map(t -> t.getMaxAttempts()).orElse(1);
        if (permanent) {
            state.transition(n, NotificationStatus.DEAD, "permanent failure: " + error);
        } else if (attemptNo - n.getAttemptBase() >= maxAttempts) {
            state.transition(n, NotificationStatus.DEAD, "retries exhausted after " + attemptNo + " attempts: " + error);
        } else {
            n.setNextAttemptAt(now.plus(retryPolicy.delayAfter(attemptNo)));
            state.transition(n, NotificationStatus.PENDING, "transient failure, will retry: " + error);
        }
    }

    private Notification fenced(String id, int attemptNo) {
        Notification n = notifications.findById(id).orElse(null);
        if (n == null || n.getStatus() != NotificationStatus.PROCESSING || n.getAttemptCount() != attemptNo) {
            log.info("Ignoring stale outcome for {} attempt {}", id, attemptNo);
            return null;
        }
        return n;
    }
}
