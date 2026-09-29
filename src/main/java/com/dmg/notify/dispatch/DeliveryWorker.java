package com.dmg.notify.dispatch;

import com.dmg.notify.channel.Channel.DeliveryRequest;
import com.dmg.notify.channel.Channel.PermanentChannelException;
import com.dmg.notify.channel.Channel.TransientChannelException;
import com.dmg.notify.channel.ChannelConfig;
import com.dmg.notify.channel.ChannelConfigRepository;
import com.dmg.notify.channel.ChannelRegistry;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationStatus;
import java.time.Clock;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private final ChannelConfigRepository channelConfigs;
    private final DeliveryOutcomeService outcomes;
    private final Clock clock;
    private final DispatchProperties props;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notify-send-watchdog");
        t.setDaemon(true);
        return t;
    });

    public DeliveryWorker(NotificationRepository notifications, ChannelRegistry channels,
                          ChannelConfigRepository channelConfigs, DeliveryOutcomeService outcomes, DispatchProperties props,
                          Clock clock) {
        this.notifications = notifications;
        this.channels = channels;
        this.channelConfigs = channelConfigs;
        this.outcomes = outcomes;
        this.props = props;
        this.clock = clock;
    }

    public void deliver(String id, int attemptNo) {
        try {
            Instant started = clock.instant();
            // restart the lease now that a thread is free; also proves we still own this attempt (fence)
            if (notifications.extendLease(id, attemptNo, started.plusSeconds(props.leaseSeconds())) == 0) return;
            Notification n = notifications.findById(id).orElse(null);
            if (n == null) return;
            try {
                String senderId = channelConfigs.findByTenantIdAndChannel(n.getTenantId(), n.getChannel())
                        .map(ChannelConfig::getSenderId).orElse(null);
                sendWithTimeout(n, senderId);
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

    /**
     * Calls the provider on this pool thread; a watchdog interrupts the thread if the provider hangs so the
     * pool thread is freed and the attempt is recorded as a transient failure (retried with the same deliveryKey).
     * A provider that ignores interrupts can still pin its thread; that is bounded by the pool size.
     */
    private void sendWithTimeout(Notification n, String senderId) {
        Thread worker = Thread.currentThread();
        Object lock = new Object();
        boolean[] finished = {false};
        boolean[] timedOut = {false};
        ScheduledFuture<?> guard = watchdog.schedule(() -> {
            synchronized (lock) {
                if (!finished[0]) { timedOut[0] = true; worker.interrupt(); }
            }
        }, props.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        try {
            channels.get(n.getChannel()).send(new DeliveryRequest(
                    n.getId(), n.getTenantId(), n.getRecipient(), n.getSubject(), n.getBody(), senderId));
        } catch (PermanentChannelException e) {
            throw e;
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (timedOut[0]) throw new TransientChannelException("provider call timed out after " + props.sendTimeoutMs() + "ms");
            }
            throw e;
        } finally {
            guard.cancel(false);
            synchronized (lock) {
                finished[0] = true;
                if (timedOut[0]) Thread.interrupted(); // never leak the watchdog's interrupt into the next task
            }
        }
    }

    @PreDestroy
    void shutdown() { watchdog.shutdownNow(); }
}
