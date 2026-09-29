package com.dmg.notify.dispatch;

import com.dmg.notify.channel.Channel.DeliveryRequest;
import com.dmg.notify.channel.Channel.PermanentChannelException;
import com.dmg.notify.channel.ChannelRegistry;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationStatus;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs on a channel pool thread. The provider call happens OUTSIDE any DB transaction.
 * If the outcome cannot be persisted (crash, DB error) the lease expires and the notification is retried
 * with the same deliveryKey, which the provider de-duplicates.
 */
@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final NotificationRepository notifications;
    private final ChannelRegistry channels;
    private final DeliveryOutcomeService outcomes;
    private final Clock clock;

    public DeliveryWorker(NotificationRepository notifications, ChannelRegistry channels,
                          DeliveryOutcomeService outcomes, Clock clock) {
        this.notifications = notifications;
        this.channels = channels;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    public void deliver(String id, int attemptNo) {
        try {
            Notification n = notifications.findById(id).orElse(null);
            if (n == null || n.getStatus() != NotificationStatus.PROCESSING || n.getAttemptCount() != attemptNo) return;
            Instant started = clock.instant();
            try {
                channels.get(n.getChannel()).send(new DeliveryRequest(
                        n.getId(), n.getTenantId(), n.getRecipient(), n.getSubject(), n.getBody()));
            } catch (PermanentChannelException e) {
                outcomes.recordFailure(id, attemptNo, e.getMessage(), true, started);
                return;
            } catch (RuntimeException e) {
                outcomes.recordFailure(id, attemptNo, String.valueOf(e.getMessage()), false, started);
                return;
            }
            outcomes.recordSuccess(id, attemptNo, started);
        } catch (RuntimeException e) {
            log.error("Delivery of {} attempt {} failed to persist outcome; lease expiry will recover", id, attemptNo, e);
        }
    }
}
