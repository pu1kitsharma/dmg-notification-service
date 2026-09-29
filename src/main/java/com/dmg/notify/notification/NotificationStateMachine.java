package com.dmg.notify.notification;

import com.dmg.notify.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single choke point for status changes: validates the transition and writes the audit event in the
 * same transaction, so state and audit trail can never diverge.
 */
@Service
public class NotificationStateMachine {
    private final NotificationRepository notifications;
    private final NotificationEventRepository events;
    private final Clock clock;

    public NotificationStateMachine(NotificationRepository notifications, NotificationEventRepository events, Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    /** Records the initial null -> PENDING event. */
    public void recordCreated(Notification n) {
        events.save(new NotificationEvent(n.getId(), n.getTenantId(), null, NotificationStatus.PENDING, 0,
                n.getScheduledAt() != null ? "accepted (scheduled)" : "accepted", clock.instant()));
    }

    /** Transition a managed entity. Must be called inside a transaction. */
    @Transactional
    public void transition(Notification n, NotificationStatus to, String reason) {
        NotificationStatus from = n.getStatus();
        if (!from.canTransitionTo(to)) {
            throw new ApiException.Conflict("Illegal transition " + from + " -> " + to + " for " + n.getId());
        }
        Instant now = clock.instant();
        n.setStatus(to);
        n.setUpdatedAt(now);
        if (to != NotificationStatus.PROCESSING) n.setLeaseUntil(null);
        events.save(new NotificationEvent(n.getId(), n.getTenantId(), from, to, n.getAttemptCount(), reason, now));
    }

    /** @return the new attempt number if this caller won the claim, empty if someone else did / not due. */
    @Transactional
    public Optional<Integer> claim(String id, Instant now, Instant leaseUntil) {
        if (notifications.claim(id, now, leaseUntil) == 0) return Optional.empty();
        Notification n = notifications.findById(id).orElseThrow();
        events.save(new NotificationEvent(id, n.getTenantId(), NotificationStatus.PENDING,
                NotificationStatus.PROCESSING, n.getAttemptCount(), "claimed by dispatcher", now));
        return Optional.of(n.getAttemptCount());
    }

    /** Give a claim back without counting it as an attempt (e.g. worker pool saturated). */
    @Transactional
    public void release(String id, String reason) {
        notifications.findById(id).filter(n -> n.getStatus() == NotificationStatus.PROCESSING).ifPresent(n -> {
            n.setAttemptCount(n.getAttemptCount() - 1);
            transition(n, NotificationStatus.PENDING, reason);
        });
    }

    @Transactional
    public Notification cancel(String id, Long tenantId) {
        Notification n = notifications.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException.NotFound("Notification " + id + " not found"));
        if (n.getStatus() != NotificationStatus.PENDING) {
            throw new ApiException.Conflict("Only PENDING notifications can be cancelled (current: " + n.getStatus() + ")");
        }
        transition(n, NotificationStatus.CANCELLED, "cancelled by tenant admin");
        return n;
    }
}
